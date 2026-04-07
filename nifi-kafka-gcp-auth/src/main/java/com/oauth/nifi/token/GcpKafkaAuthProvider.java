/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.oauth.nifi.token;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.ExternalAccountCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.cloud.iam.credentials.v1.IamCredentialsSettings;
import com.google.cloud.iam.credentials.v1.SignJwtRequest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.gcp.credentials.service.GCPCredentialsService;
import org.apache.nifi.oauth2.AccessToken;
import org.apache.nifi.oauth2.OAuth2AccessTokenProvider;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.gcp.util.GoogleUtils;
import org.apache.nifi.reporting.InitializationException;

@Tags({ "gcp", "oauth2", "provider", "authorization", "access token", "http", "kafka", "oauthbearer" })
@CapabilityDescription(
    "Provides OAuth 2.0 access tokens for Google REST APIs and GCP Managed Kafka (SASL/OAUTHBEARER). " +
    "Enable 'Kafka Mode' when using with Kafka3ConnectionService to wrap the GCP access token in the " +
    "pseudo-JWT format required by Kafka's OAuthBearerLoginModule.")
@SeeAlso({ OAuth2AccessTokenProvider.class, GCPCredentialsService.class })
public class GcpKafkaAuthProvider
    extends AbstractControllerService
    implements OAuth2AccessTokenProvider {

    // Pseudo-JWT header as defined by Google's GcpLoginCallbackHandler
    private static final String KAFKA_JWT_HEADER =
        new Gson().toJson(Map.of("typ", "JWT", "alg", "GOOG_OAUTH2_TOKEN"));

    private static final String DEFAULT_SCOPE =
        "https://www.googleapis.com/auth/cloud-platform";

    public static final PropertyDescriptor SCOPE =
        new PropertyDescriptor.Builder()
            .name("scope")
            .displayName("Scope")
            .description(
                "Whitespace-delimited, case-sensitive list of OAuth2 scopes. " +
                "More information: https://developers.google.com/identity/protocols/oauth2/scopes"
            )
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue(DEFAULT_SCOPE)
            .build();

    public static final PropertyDescriptor SERVICE_ACCOUNT =
        new PropertyDescriptor.Builder()
            .name("impersonate-service-account")
            .displayName("Impersonate Service Account")
            .description(
                "Service account email to impersonate. The source project must enable the 'IAMCredentials' API. " +
                "The target service account must grant the originating principal the 'Service Account Token Creator' IAM role."
            )
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor DELEGATE =
        new PropertyDescriptor.Builder()
            .name("delegate")
            .displayName("Domain-wide Delegation")
            .description(
                "User email for G Suite domain-wide delegation. " +
                "Requires Google Workspace and appropriate configuration."
            )
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROJECT_ID =
        new PropertyDescriptor.Builder()
            .name("project-id")
            .displayName("Quota Project ID")
            .description(
                "Custom quota/billing project for IAM Credentials API calls."
            )
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .dependsOn(SERVICE_ACCOUNT)
            .dependsOn(DELEGATE)
            .build();

    public static final PropertyDescriptor KAFKA_MODE =
        new PropertyDescriptor.Builder()
            .name("kafka-mode")
            .displayName("Kafka Mode (OAUTHBEARER)")
            .description(
                "When enabled, wraps the GCP access token in a pseudo-JWT format required by " +
                "Kafka's SASL/OAUTHBEARER mechanism (GCP Managed Kafka). " +
                "Enable when using with Kafka3ConnectionService. " +
                "Disable (default) when using with InvokeHTTP or other Google REST API calls."
            )
            .required(true)
            .allowableValues("true", "false")
            .defaultValue("false")
            .build();

    private static final List<PropertyDescriptor> properties;

    static {
        final List<PropertyDescriptor> props = new ArrayList<>();
        props.add(GoogleUtils.GCP_CREDENTIALS_PROVIDER_SERVICE);
        props.add(SCOPE);
        props.add(SERVICE_ACCOUNT);
        props.add(DELEGATE);
        props.add(PROJECT_ID);
        props.add(KAFKA_MODE);
        properties = Collections.unmodifiableList(props);
    }

    private volatile GoogleCredentials googleCredentials;
    private volatile IamCredentialsClient iamCredentialsClient;
    private volatile AccessToken accessToken;
    private volatile String serviceAccount;
    private volatile String scope;
    private volatile String delegate;
    private volatile String projectId;
    private volatile boolean kafkaMode;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context)
        throws InitializationException, IOException {
        googleCredentials = null;
        iamCredentialsClient = null;
        accessToken = null;

        GCPCredentialsService gcpCredentialsService = context
            .getProperty(GoogleUtils.GCP_CREDENTIALS_PROVIDER_SERVICE)
            .asControllerService(GCPCredentialsService.class);

        googleCredentials = gcpCredentialsService
            .getGoogleCredentials()
            .createScoped(DEFAULT_SCOPE);

        scope = context.getProperty(SCOPE).getValue().replaceAll("\\s+", " ");
        serviceAccount = context.getProperty(SERVICE_ACCOUNT).getValue();
        delegate = context.getProperty(DELEGATE).getValue();
        projectId = context.getProperty(PROJECT_ID).getValue();
        kafkaMode = Boolean.parseBoolean(context.getProperty(KAFKA_MODE).getValue());

        if (serviceAccount != null) {
            if (delegate != null) {
                IamCredentialsSettings.Builder builder =
                    IamCredentialsSettings.newBuilder()
                        .setCredentialsProvider(
                            FixedCredentialsProvider.create(googleCredentials)
                        );
                if (projectId != null) {
                    builder.setQuotaProjectId(projectId);
                }
                iamCredentialsClient = IamCredentialsClient.create(builder.build());
                return;
            }

            googleCredentials = ImpersonatedCredentials.newBuilder()
                .setSourceCredentials(googleCredentials)
                .setTargetPrincipal(serviceAccount)
                .setScopes(Arrays.asList(scope.split(" ")))
                .build();
            return;
        }

        googleCredentials = googleCredentials.createScoped(scope.split(" "));

        if (delegate != null) {
            googleCredentials = googleCredentials.createDelegated(delegate);
        }
    }

    @Override
    public AccessToken getAccessDetails() {
        if (accessToken != null && !accessToken.isExpired()) {
            return accessToken;
        }

        com.google.auth.oauth2.AccessToken gcpAccessToken;
        if (serviceAccount != null && delegate != null) {
            gcpAccessToken = getJwtCredentials();
        } else {
            gcpAccessToken = getDefaultCredentials();
        }

        if (gcpAccessToken == null) {
            return null;
        }

        Long expiresIn = Duration.between(
            Instant.now(),
            gcpAccessToken.getExpirationTime().toInstant()
        ).getSeconds();

        String tokenValue;
        if (kafkaMode) {
            String subject = extractSubject();
            tokenValue = wrapAsKafkaJwt(gcpAccessToken, subject);
        } else {
            tokenValue = gcpAccessToken.getTokenValue();
        }

        accessToken = new AccessToken(tokenValue, null, "Bearer", expiresIn, scope);
        return accessToken;
    }

    /**
     * Extracts the principal identity from the configured Google credentials.
     * Used to populate the "sub" claim in the Kafka pseudo-JWT.
     */
    private String extractSubject() {
        if (serviceAccount != null) {
            return serviceAccount;
        }
        if (googleCredentials instanceof ServiceAccountCredentials) {
            return ((ServiceAccountCredentials) googleCredentials).getClientEmail();
        }
        if (googleCredentials instanceof ComputeEngineCredentials) {
            return ((ComputeEngineCredentials) googleCredentials).getAccount();
        }
        if (googleCredentials instanceof ImpersonatedCredentials) {
            return ((ImpersonatedCredentials) googleCredentials).getAccount();
        }
        if (googleCredentials instanceof ExternalAccountCredentials) {
            String email = ((ExternalAccountCredentials) googleCredentials).getServiceAccountEmail();
            return email != null ? email : "";
        }
        return "";
    }

    /**
     * Wraps a raw GCP access token in a pseudo-JWT structure as required by
     * Kafka's OAuthBearerLoginModule and compatible with GCP Managed Kafka.
     *
     * Format: Base64URL(header) + "." + Base64URL(payload) + "." + Base64URL(accessToken)
     */
    private static String wrapAsKafkaJwt(
        com.google.auth.oauth2.AccessToken gcpToken, String subject) {

        long expEpochSecond = gcpToken.getExpirationTime().toInstant().getEpochSecond();
        long iatEpochSecond = Instant.now().getEpochSecond();

        String payload = new Gson().toJson(Map.of(
            "exp", expEpochSecond,
            "iat", iatEpochSecond,
            "scope", "kafka",
            "sub", subject
        ));

        return b64Encode(KAFKA_JWT_HEADER)
            + "." + b64Encode(payload)
            + "." + b64Encode(gcpToken.getTokenValue());
    }

    private static String b64Encode(String data) {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(data.getBytes(StandardCharsets.UTF_8));
    }

    private com.google.auth.oauth2.AccessToken getDefaultCredentials() {
        try {
            googleCredentials.refreshIfExpired();
            return googleCredentials.getAccessToken();
        } catch (IOException e) {
            getLogger().error(e.getMessage(), e);
            return null;
        }
    }

    private com.google.auth.oauth2.AccessToken getJwtCredentials() {
        try {
            long iat = Instant.now().getEpochSecond();
            long exp = iat + 3600;
            String payload = new Gson().toJson(Map.of(
                "aud", "https://oauth2.googleapis.com/token",
                "iat", iat,
                "exp", exp,
                "iss", serviceAccount,
                "scope", scope,
                "sub", delegate
            ));

            SignJwtRequest signJwtRequest = SignJwtRequest.newBuilder()
                .setName("projects/-/serviceAccounts/" + serviceAccount)
                .setPayload(payload)
                .build();

            String assertion = iamCredentialsClient.signJwt(signJwtRequest).getSignedJwt();

            String body = URLEncodedUtils.format(
                List.of(
                    new BasicNameValuePair("assertion", assertion),
                    new BasicNameValuePair("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                ),
                "utf-8"
            );

            HttpRequest httpRequest = new NetHttpTransport()
                .createRequestFactory()
                .buildPostRequest(
                    new GenericUrl("https://oauth2.googleapis.com/token"),
                    ByteArrayContent.fromString("application/x-www-form-urlencoded", body)
                );

            JsonObject json = JsonParser.parseString(
                httpRequest.execute().parseAsString()
            ).getAsJsonObject();

            return new com.google.auth.oauth2.AccessToken(
                json.get("access_token").getAsString(),
                Date.from(Instant.now().plusSeconds(json.get("expires_in").getAsLong()))
            );
        } catch (IOException | JsonSyntaxException e) {
            getLogger().error(e.getMessage(), e);
            return null;
        }
    }
}
