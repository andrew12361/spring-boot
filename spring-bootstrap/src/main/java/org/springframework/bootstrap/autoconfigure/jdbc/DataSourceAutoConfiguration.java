/*
 * Copyright 2012-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.bootstrap.autoconfigure.jdbc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.bootstrap.context.annotation.ConditionalOnClass;
import org.springframework.bootstrap.context.annotation.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * @author Dave Syer
 * 
 */
@Configuration
@ConditionalOnClass(EmbeddedDatabaseType.class /* Spring JDBC */)
// @ConditionalOnMissingBean(DataSource.class)
public class DataSourceAutoConfiguration {

	private static Log logger = LogFactory.getLog(DataSourceAutoConfiguration.class);

	@Autowired(required = false)
	private DataSource dataSource;

	@Autowired
	private ApplicationContext applicationContext;

	@Conditional(DataSourceAutoConfiguration.EmbeddedDatabaseCondition.class)
	@Import(EmbeddedDatabaseConfiguration.class)
	protected static class EmbeddedConfiguration {
	}

	@Conditional(DataSourceAutoConfiguration.TomcatDatabaseCondition.class)
	@Import(TomcatDataSourceConfiguration.class)
	protected static class TomcatConfiguration {
	}

	@Conditional(DataSourceAutoConfiguration.BasicDatabaseCondition.class)
	@Import(BasicDataSourceConfiguration.class)
	protected static class DbcpConfiguration {
	}

	@Configuration
	@Conditional(DataSourceAutoConfiguration.SomeDatabaseCondition.class)
	// FIXME: make this @ConditionalOnBean(DataSource.class)
	protected static class JdbcTemplateConfiguration {

		@Autowired(required = false)
		private DataSource dataSource;

		@Bean
		@ConditionalOnMissingBean(JdbcOperations.class)
		public JdbcOperations jdbcTemplate() {
			return new JdbcTemplate(this.dataSource);
		}

		@Bean
		@ConditionalOnMissingBean(NamedParameterJdbcOperations.class)
		public NamedParameterJdbcOperations namedParameterJdbcTemplate() {
			return new NamedParameterJdbcTemplate(this.dataSource);
		}

	}

	// FIXME: DB platform
	@Value("${spring.database.schema:classpath*:schema.sql}")
	private String schemaLocations = "";

	@PostConstruct
	protected void initialize() throws Exception {
		if (this.dataSource == null) {
			logger.debug("No DataSource found so not initializing");
			return;
		}
		ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
		boolean exists = false;
		List<Resource> resources = new ArrayList<Resource>();
		for (String location : StringUtils
				.commaDelimitedListToStringArray(this.schemaLocations)) {
			resources
					.addAll(Arrays.asList(this.applicationContext.getResources(location)));
		}
		for (Resource resource : resources) {
			if (resource.exists()) {
				exists = true;
				populator.addScript(resource);
				populator.setContinueOnError(true);
			}
		}
		if (exists) {
			DatabasePopulatorUtils.execute(populator, this.dataSource);
		}
	}

	static class SomeDatabaseCondition implements Condition {

		protected Log logger = LogFactory.getLog(getClass());

		private Condition tomcatCondition = new TomcatDatabaseCondition();

		private Condition dbcpCondition = new BasicDatabaseCondition();

		private Condition embeddedCondition = new EmbeddedDatabaseCondition();

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			if (this.tomcatCondition.matches(context, metadata)
					|| this.dbcpCondition.matches(context, metadata)
					|| this.embeddedCondition.matches(context, metadata)) {
				if (this.logger.isDebugEnabled()) {
					this.logger
							.debug("Existing auto database detected: match result true");
				}
				return true;
			}
			if (BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
					context.getBeanFactory(), DataSource.class, true, false).length > 0) {
				if (this.logger.isDebugEnabled()) {
					this.logger
							.debug("Existing bean configured database detected: match result true");
				}
				return true;
			}
			return false;
		}

	}

	static class TomcatDatabaseCondition extends NonEmbeddedDatabaseCondition {

		@Override
		protected String getDataSourecClassName() {
			return "org.apache.tomcat.jdbc.pool.DataSource";
		}

	}

	static class BasicDatabaseCondition extends NonEmbeddedDatabaseCondition {

		private Condition condition = new TomcatDatabaseCondition();

		@Override
		protected String getDataSourecClassName() {
			return "org.apache.commons.dbcp.BasicDataSource";
		}

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			if (this.condition.matches(context, metadata)) {
				return false; // prefer Tomcat pool
			}
			return super.matches(context, metadata);
		}

	}

	static abstract class NonEmbeddedDatabaseCondition implements Condition {

		protected Log logger = LogFactory.getLog(getClass());

		protected abstract String getDataSourecClassName();

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			if (!ClassUtils.isPresent(getDataSourecClassName(), null)) {
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Tomcat DataSource pool not found");
				}
				return false;
			}
			String driverClassName = context.getEnvironment().getProperty(
					"spring.database.driverClassName");
			String url = context.getEnvironment().getProperty("spring.database.url");
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Spring JDBC detected (embedded database type is "
						+ EmbeddedDatabaseConfiguration.getEmbeddedDatabaseType() + ").");
			}
			if (driverClassName == null) {
				driverClassName = EmbeddedDatabaseConfiguration
						.getEmbeddedDatabaseDriverClass(EmbeddedDatabaseConfiguration
								.getEmbeddedDatabaseType());
			}
			if (url == null) {
				url = EmbeddedDatabaseConfiguration
						.getEmbeddedDatabaseUrl(EmbeddedDatabaseConfiguration
								.getEmbeddedDatabaseType());
			}
			if (driverClassName != null && url != null
					&& ClassUtils.isPresent(driverClassName, null)) {
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Driver class " + driverClassName + " found");
				}
				return true;
			}
			return false;
		}

	}

	static class EmbeddedDatabaseCondition implements Condition {

		protected Log logger = LogFactory.getLog(getClass());

		private Condition tomcatCondition = new TomcatDatabaseCondition();

		private Condition dbcpCondition = new BasicDatabaseCondition();

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			if (this.tomcatCondition.matches(context, metadata)
					|| this.dbcpCondition.matches(context, metadata)) {
				if (this.logger.isDebugEnabled()) {
					this.logger
							.debug("Existing non-embedded database detected: match result false");
				}
				return false;
			}
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Spring JDBC detected (embedded database type is "
						+ EmbeddedDatabaseConfiguration.getEmbeddedDatabaseType() + ").");
			}
			return EmbeddedDatabaseConfiguration.getEmbeddedDatabaseType() != null;
		}
	}

}