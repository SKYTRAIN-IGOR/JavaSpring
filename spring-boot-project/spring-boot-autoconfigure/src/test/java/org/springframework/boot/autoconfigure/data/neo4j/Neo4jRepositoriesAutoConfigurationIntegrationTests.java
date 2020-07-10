/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.autoconfigure.data.neo4j;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.country.CountryRepository;
import org.springframework.boot.autoconfigure.neo4j.Neo4jDriverAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Michael J. Simons
 */
@SpringBootTest(properties = "spring.data.neo4j.repositories.type=imperative")
@Testcontainers(disabledWithoutDocker = true)
public class Neo4jRepositoriesAutoConfigurationIntegrationTests {

	@Container
	private static Neo4jContainer<?> neo4jServer = new Neo4jContainer<>("neo4j:4.0");

	@DynamicPropertySource
	static void neo4jProperties(DynamicPropertyRegistry registry) {

		registry.add("spring.neo4j.uri", neo4jServer::getBoltUrl);
		registry.add("spring.neo4j.authentication.username", () -> "neo4j");
		registry.add("spring.neo4j.authentication.password", neo4jServer::getAdminPassword);
	}

	private final CountryRepository countryRepository;

	@Autowired
	Neo4jRepositoriesAutoConfigurationIntegrationTests(CountryRepository countryRepository) {
		this.countryRepository = countryRepository;
	}

	@Test
	void ensureRepositoryIsReady() {

		assertThat(countryRepository.count()).isEqualTo(0);
	}

	@Configuration
	@EnableNeo4jRepositories(basePackageClasses = CountryRepository.class)
	@ImportAutoConfiguration({ Neo4jDriverAutoConfiguration.class, Neo4jDataAutoConfiguration.class,
			Neo4jRepositoriesAutoConfiguration.class })
	static class TestConfiguration {

	}

}
