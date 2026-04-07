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

import org.apache.nifi.processors.gcp.credentials.service.GCPCredentialsControllerService;
import org.apache.nifi.processors.gcp.util.GoogleUtils;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestGcpKafkaAuthProvider {

    @BeforeEach
    public void init() {}

    @Test
    public void testService() throws InitializationException {
        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);
        final GCPCredentialsControllerService credentialsService =
            new GCPCredentialsControllerService();
        final GcpKafkaAuthProvider tokenProviderService = new GcpKafkaAuthProvider();

        runner.addControllerService("test-gcp-credentials-controller-service", credentialsService);
        runner.enableControllerService(credentialsService);
        runner.assertValid(credentialsService);

        runner.addControllerService("test-gcp-kafka-auth-provider", tokenProviderService);
        runner.setProperty(
            tokenProviderService,
            GoogleUtils.GCP_CREDENTIALS_PROVIDER_SERVICE,
            "test-gcp-credentials-controller-service"
        );
        runner.setProperty(
            tokenProviderService,
            GcpKafkaAuthProvider.SCOPE,
            "https://www.googleapis.com/auth/cloud-platform"
        );
        runner.setProperty(
            tokenProviderService,
            GcpKafkaAuthProvider.KAFKA_MODE,
            "false"
        );
        runner.enableControllerService(tokenProviderService);
        runner.assertValid(tokenProviderService);
    }
}
