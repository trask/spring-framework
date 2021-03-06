/*
 * Copyright 2002-2014 the original author or authors.
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

package org.springframework.orm.jpa.vendor;

import java.util.Map;
import javax.persistence.EntityManagerFactory;
import javax.persistence.spi.PersistenceUnitInfo;

import org.hibernate.cfg.Configuration;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.PersistenceUnitInfoDescriptor;
import org.hibernate.service.ServiceRegistry;

import org.springframework.orm.jpa.persistenceunit.SmartPersistenceUnitInfo;

/**
 * Spring-specific subclass of the standard {@link HibernatePersistenceProvider}
 * from the {@code org.hibernate.jpa} package, adding support for
 * {@link SmartPersistenceUnitInfo#getManagedPackages()}.
 *
 * <p>Compatible with Hibernate 4.3. {@link SpringHibernateEjbPersistenceProvider}
 * is an alternative for compatibility with earlier Hibernate versions (3.6-4.2).
 *
 * @author Juergen Hoeller
 * @since 4.1
 */
class SpringHibernateJpaPersistenceProvider extends HibernatePersistenceProvider {

	@Override
	@SuppressWarnings("rawtypes")
	public EntityManagerFactory createContainerEntityManagerFactory(final PersistenceUnitInfo info, Map properties) {
		return new EntityManagerFactoryBuilderImpl(new PersistenceUnitInfoDescriptor(info), properties) {
			@Override
			public Configuration buildHibernateConfiguration(ServiceRegistry serviceRegistry) {
				Configuration configuration = super.buildHibernateConfiguration(serviceRegistry);
				if (info instanceof SmartPersistenceUnitInfo) {
					for (String managedPackage : ((SmartPersistenceUnitInfo) info).getManagedPackages()) {
						configuration.addPackage(managedPackage);
					}
				}
				return configuration;
			}
		}.build();
	}

}
