package com.adc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource("classpath:auth-test.properties")
@ActiveProfiles("demo")
class AdcBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
