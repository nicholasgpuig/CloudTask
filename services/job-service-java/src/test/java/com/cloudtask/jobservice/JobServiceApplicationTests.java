package com.cloudtask.jobservice;

import com.cloudtask.jobservice.messaging.JobPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class JobServiceApplicationTests {

	@MockitoBean
	private JobPublisher jobPublisher;

	@Test
	void contextLoads() {
	}

}
