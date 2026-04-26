
package com.securechat.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import com.securechat.config.TestSecurityConfig;
import org.springframework.context.annotation.Import;

@SpringBootTest  // Loads complete Spring Boot application context with all beans configured
@ActiveProfiles("test")  // Activates 'test' profile for test-specific configuration (e.g., H2 database)
@Import(TestSecurityConfig.class)
class SecureChatTest {

	@Test
	void contextLoads() {
		// This test verifies that the Spring application context loads successfully
	}
}
