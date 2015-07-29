/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
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
package org.wildfly.extension.elytron;

import static org.wildfly.extension.elytron.Capabilities.PROVIDERS_CAPABILITY;
import static org.wildfly.extension.elytron.Capabilities.SASL_SERVER_FACTORY_CAPABILITY;
import static org.wildfly.extension.elytron.Capabilities.SASL_SERVER_FACTORY_RUNTIME_CAPABILITY;
import static org.wildfly.extension.elytron.ClassLoadingAttributeDefinitions.MODULE;
import static org.wildfly.extension.elytron.ClassLoadingAttributeDefinitions.SLOT;
import static org.wildfly.extension.elytron.ClassLoadingAttributeDefinitions.resolveClassLoader;
import static org.wildfly.extension.elytron.ElytronDefinition.commonDependencies;
import static org.wildfly.extension.elytron.ElytronDescriptionConstants.KEY;
import static org.wildfly.extension.elytron.ElytronDescriptionConstants.PROPERTY;
import static org.wildfly.extension.elytron.ElytronDescriptionConstants.VALUE;
import static org.wildfly.extension.elytron.ElytronExtension.asDoubleIfDefined;
import static org.wildfly.extension.elytron.ElytronExtension.asStringIfDefined;
import static org.wildfly.extension.elytron.SaslFactoryRuntimeResource.wrap;
import static org.wildfly.extension.elytron.SecurityActions.doPrivileged;

import java.security.PrivilegedExceptionAction;
import java.security.Provider;
import java.security.Security;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.security.sasl.SaslServerFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.AttributeMarshaller;
import org.jboss.as.controller.ObjectListAttributeDefinition;
import org.jboss.as.controller.ObjectTypeAttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.RestartParentWriteAttributeHandler;
import org.jboss.as.controller.ServiceRemoveStepHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleMapAttributeDefinition;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.operations.validation.EnumValidator;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.dmr.Property;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartException;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.extension.elytron.TrivialService.ValueSupplier;
import org.wildfly.security.sasl.util.AggregateSaslServerFactory;
import org.wildfly.security.sasl.util.FilterMechanismSaslServerFactory;
import org.wildfly.security.sasl.util.MechanismProviderFilteringSaslServerFactory;
import org.wildfly.security.sasl.util.PropertiesSaslServerFactory;
import org.wildfly.security.sasl.util.ProtocolSaslServerFactory;
import org.wildfly.security.sasl.util.SaslMechanismInformation;
import org.wildfly.security.sasl.util.SecurityProviderSaslServerFactory;
import org.wildfly.security.sasl.util.ServerNameSaslServerFactory;
import org.wildfly.security.sasl.util.ServiceLoaderSaslServerFactory;

/**
 * The {@link ResourceDefinition} instances for the {@link SaslServerFactory} resources.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
class SaslServerDefinitions {

    static final SimpleAttributeDefinition SERVER_NAME = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.SERVER_NAME, ModelType.STRING, true)
        .setMinSize(1)
        .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
        .build();

    static final SimpleAttributeDefinition PROTOCOL = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.PROTOCOL, ModelType.STRING, true)
        .setMinSize(1)
        .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
        .build();

    static final SimpleAttributeDefinition SASL_SERVER_FACTORY = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.SASL_SERVER_FACTORY, ModelType.STRING, false)
        .setMinSize(1)
        .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
        .setCapabilityReference(SASL_SERVER_FACTORY_CAPABILITY, SASL_SERVER_FACTORY_CAPABILITY, true)
        .build();

    static final SimpleAttributeDefinition PROVIDER_LOADER = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.PROVIDER_LOADER, ModelType.STRING, true)
        .setAllowExpression(true)
        .setMinSize(1)
        .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
        .setCapabilityReference(PROVIDERS_CAPABILITY, SASL_SERVER_FACTORY_CAPABILITY, true)
        .build();

    static final SimpleAttributeDefinition ENABLING = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.ENABLING, ModelType.BOOLEAN, false)
        .setAllowExpression(true)
        .setDefaultValue(new ModelNode(true))
        .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
        .build();

    static final SimpleAttributeDefinition MECHANISM_NAME = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.MECHANISM_NAME, ModelType.STRING, true)
        .setAllowExpression(true)
        .build();

    static final SimpleAttributeDefinition PROVIDER_NAME = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.PROVIDER_NAME, ModelType.STRING, false)
        .setAllowExpression(true)
        .build();

    static final SimpleAttributeDefinition PROVIDER_VERSION = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.PROVIDER_VERSION, ModelType.DOUBLE, true)
        .setAllowExpression(true)
        .build();

    static final SimpleAttributeDefinition VERSION_COMPARISON = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.VERSION_COMPARISON, ModelType.STRING, true)
        .setAllowExpression(true)
        .setRequires(ElytronDescriptionConstants.PROVIDER_VERSION)
        .setAllowedValues(ElytronDescriptionConstants.LESS_THAN, ElytronDescriptionConstants.GREATER_THAN)
        .setValidator(EnumValidator.create(Comparison.class, true, true))
        .setMinSize(1)
        .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
        .build();

    static final ObjectTypeAttributeDefinition MECH_PROVIDER_FILTER = new ObjectTypeAttributeDefinition.Builder(ElytronDescriptionConstants.FILTER, MECHANISM_NAME, PROVIDER_NAME, PROVIDER_VERSION, VERSION_COMPARISON)
        .build();


    static final ObjectListAttributeDefinition MECH_PROVIDER_FILTERS = new ObjectListAttributeDefinition.Builder(ElytronDescriptionConstants.FILTERS, MECH_PROVIDER_FILTER)
        .setMinSize(1)
        .build();

    static final SimpleMapAttributeDefinition PROPERTIES = new SimpleMapAttributeDefinition.Builder(ElytronDescriptionConstants.PROPERTIES, ModelType.STRING, true)
        .setAttributeMarshaller(new AttributeMarshaller() {

            @Override
            public void marshallAsElement(AttributeDefinition attribute, ModelNode resourceModel, boolean marshallDefault,
                    XMLStreamWriter writer) throws XMLStreamException {
                resourceModel = resourceModel.get(attribute.getName());
                if (resourceModel.isDefined()) {
                    writer.writeStartElement(attribute.getName());
                    for (ModelNode property : resourceModel.asList()) {
                        writer.writeEmptyElement(PROPERTY);
                        writer.writeAttribute(KEY, property.asProperty().getName());
                        writer.writeAttribute(VALUE, property.asProperty().getValue().asString());
                    }
                    writer.writeEndElement();
                }
            }

        }).build();

    static final SimpleAttributeDefinition PREDEFINED_FILTER = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.PREDEFINED_FILTER, ModelType.STRING, true)
        .setAllowExpression(true)
        .setAllowedValues(NamePredicate.names())
        .setValidator(EnumValidator.create(NamePredicate.class, true, true))
        .setMinSize(1)
        .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
        .setAlternatives(ElytronDescriptionConstants.PATTERN_FILTER)
        .build();

    static final SimpleAttributeDefinition PATTERN_FILTER = new SimpleAttributeDefinitionBuilder(RegexAttributeDefinitions.PATTERN)
        .setName(ElytronDescriptionConstants.PATTERN_FILTER)
        .setAlternatives(ElytronDescriptionConstants.PREDEFINED_FILTER)
        .build();

    static final ObjectTypeAttributeDefinition CONFIGURED_FILTER = new ObjectTypeAttributeDefinition.Builder(ElytronDescriptionConstants.FILTER, PREDEFINED_FILTER, PATTERN_FILTER, ENABLING)
        .build();

    static final ObjectListAttributeDefinition CONFIGURED_FILTERS = new ObjectListAttributeDefinition.Builder(ElytronDescriptionConstants.FILTERS, CONFIGURED_FILTER)
        .build();

    private static final AggregateComponentDefinition<SaslServerFactory> AGGREGATE_SASL_SERVER_FACTORY = AggregateComponentDefinition.create(SaslServerFactory.class,
            ElytronDescriptionConstants.AGGREGATE_SASL_SERVER_FACTORY, ElytronDescriptionConstants.SASL_SERVER_FACTORIES, SASL_SERVER_FACTORY_RUNTIME_CAPABILITY,
            (SaslServerFactory[] n) -> new AggregateSaslServerFactory(n));

    static AggregateComponentDefinition<SaslServerFactory> getRawAggregateSaslServerFactoryDefinition() {
        return AGGREGATE_SASL_SERVER_FACTORY;
    }

    static ResourceDefinition getAggregateSaslServerFactoryDefinition() {
        return wrap(AGGREGATE_SASL_SERVER_FACTORY, SaslServerDefinitions::getSaslServerFactory);
    }

    static ResourceDefinition getConfiguredSaslServerFactoryDefinition() {
        AttributeDefinition[] attributes = new AttributeDefinition[] { SASL_SERVER_FACTORY, SERVER_NAME, PROTOCOL, PROPERTIES, CONFIGURED_FILTERS };
        AbstractAddStepHandler add = new SaslServerAddHander(attributes) {

            @Override
            protected ServiceBuilder<SaslServerFactory> installService(OperationContext context,
                    ServiceName saslServerFactoryName, ModelNode model) throws OperationFailedException {

                final String saslServerFactory = SASL_SERVER_FACTORY.resolveModelAttribute(context, model).asString();
                final String protocol = asStringIfDefined(context, PROTOCOL, model);
                final String serverName = asStringIfDefined(context, SERVER_NAME, model);

                final Map<String, String> propertiesMap;
                ModelNode properties = PROPERTIES.resolveModelAttribute(context, model);
                if (properties.isDefined()) {
                    propertiesMap = new HashMap<String, String>();
                    properties.asPropertyList().forEach((Property p) -> propertiesMap.put(p.getName(), p.getValue().asString()));
                } else {
                    propertiesMap = null;
                }

                final Predicate<String> finalFilter;
                if (model.hasDefined(ElytronDescriptionConstants.FILTERS)) {
                    Predicate<String> filter = null;
                    List<ModelNode> nodes = model.require(ElytronDescriptionConstants.FILTERS).asList();
                    for (ModelNode current : nodes) {
                        Predicate<String> currentFilter = (String s) -> true;
                        String predefinedFilter = asStringIfDefined(context, PREDEFINED_FILTER, current);
                        if (predefinedFilter != null) {
                            currentFilter = NamePredicate.valueOf(predefinedFilter).predicate;
                        } else {
                            String patternFilter = asStringIfDefined(context, PATTERN_FILTER, current);
                            if (patternFilter != null) {
                                final Pattern pattern = Pattern.compile(patternFilter);
                                currentFilter = (String s) ->  pattern.matcher(s).find();
                            }
                        }

                        currentFilter = ENABLING.resolveModelAttribute(context, current).asBoolean() ? currentFilter : currentFilter.negate();
                        filter = filter == null ? currentFilter : filter.or(currentFilter);
                    }
                    finalFilter = filter;
                } else {
                    finalFilter = null;
                }


                final InjectedValue<SaslServerFactory> saslServerFactoryInjector = new InjectedValue<SaslServerFactory>();

                TrivialService<SaslServerFactory> saslServiceFactoryService = new TrivialService<SaslServerFactory>(() -> {
                    SaslServerFactory theFactory = saslServerFactoryInjector.getValue();
                    theFactory = protocol != null ? new ProtocolSaslServerFactory(theFactory, protocol) : theFactory;
                    theFactory = serverName != null ? new ServerNameSaslServerFactory(theFactory, serverName) : theFactory;
                    theFactory = propertiesMap != null ? new PropertiesSaslServerFactory(theFactory, propertiesMap) : theFactory;
                    theFactory = finalFilter != null ? new FilterMechanismSaslServerFactory(theFactory, finalFilter) : theFactory;
                    return theFactory;
                });

                ServiceTarget serviceTarget = context.getServiceTarget();

                ServiceBuilder<SaslServerFactory> serviceBuilder = serviceTarget.addService(saslServerFactoryName, saslServiceFactoryService);

                serviceBuilder.addDependency(context.getCapabilityServiceName(
                        RuntimeCapability.buildDynamicCapabilityName(SASL_SERVER_FACTORY_CAPABILITY, saslServerFactory),
                        SaslServerFactory.class), SaslServerFactory.class, saslServerFactoryInjector);

                return serviceBuilder;
            }

        };

        return wrap(new SaslServerResourceDefinition(ElytronDescriptionConstants.CONFIGURABLE_SASL_SERVER_FACTORY, add, attributes), SaslServerDefinitions::getSaslServerFactory);
    }

    static ResourceDefinition getProviderSaslServerFactoryDefintion() {
        AbstractAddStepHandler add = new SaslServerAddHander(PROVIDER_LOADER) {

            @Override
            protected ServiceBuilder<SaslServerFactory> installService(OperationContext context,
                    ServiceName saslServerFactoryName, ModelNode model) throws OperationFailedException {

                String provider = asStringIfDefined(context, PROVIDER_LOADER, model);

                final InjectedValue<Provider[]> providerInjector = new InjectedValue<Provider[]>();
                final Supplier<Provider[]> providerSupplier = provider != null ? (() -> providerInjector.getValue()) : (() -> Security.getProviders());

                TrivialService<SaslServerFactory> saslServiceFactoryService = new TrivialService<SaslServerFactory>(() -> new SecurityProviderSaslServerFactory(providerSupplier));

                ServiceTarget serviceTarget = context.getServiceTarget();

                ServiceBuilder<SaslServerFactory> serviceBuilder = serviceTarget.addService(saslServerFactoryName, saslServiceFactoryService);

                if (provider != null) {
                    serviceBuilder.addDependency(context.getCapabilityServiceName(RuntimeCapability.buildDynamicCapabilityName(PROVIDERS_CAPABILITY, provider),
                            Provider[].class), Provider[].class, providerInjector);
                }

                return serviceBuilder;
            }
        };

        return wrap(new SaslServerResourceDefinition(ElytronDescriptionConstants.PROVIDER_SASL_SERVER_FACTORY, add, PROVIDER_LOADER), SaslServerDefinitions::getSaslServerFactory);
    }

    static ResourceDefinition getServiceLoaderSaslServerFactoryDefinition() {
        AbstractAddStepHandler add = new SaslServerAddHander(MODULE, SLOT) {

            @Override
            protected ValueSupplier<SaslServerFactory> getValueSupplier(OperationContext context, ModelNode model)
                    throws OperationFailedException {

                final String module = asStringIfDefined(context, MODULE, model);
                final String slot = asStringIfDefined(context, SLOT, model);

                return () -> getSaslServerFactory(module, slot);
            }

            private SaslServerFactory getSaslServerFactory(final String module, final String slot) throws StartException {
                try {
                    ClassLoader classLoader = doPrivileged((PrivilegedExceptionAction<ClassLoader>) () -> resolveClassLoader(module, slot));

                    return new ServiceLoaderSaslServerFactory(classLoader);
                } catch (Exception e) {
                    throw new StartException(e);
                }
            }
        };

        return wrap(new SaslServerResourceDefinition(ElytronDescriptionConstants.SERVICE_LOADER_SASL_SERVER_FACTORY, add, MODULE, SLOT), SaslServerDefinitions::getSaslServerFactory);
    }

    static ResourceDefinition getMechanismProviderFilteringSaslServerFactory() {
        AttributeDefinition[] attributes = new AttributeDefinition[] { SASL_SERVER_FACTORY, ENABLING, MECH_PROVIDER_FILTERS };
        AbstractAddStepHandler add = new SaslServerAddHander(attributes) {

            @Override
            protected ServiceBuilder<SaslServerFactory> installService(OperationContext context,
                    ServiceName saslServerFactoryName, ModelNode model) throws OperationFailedException {

                final String saslServerFactory = SASL_SERVER_FACTORY.resolveModelAttribute(context, model).asString();

                BiPredicate<String, Provider> predicate = null;

                List<ModelNode> nodes = model.require(ElytronDescriptionConstants.FILTERS).asList();
                for (ModelNode current : nodes) {
                    final String mechanismName = asStringIfDefined(context, MECHANISM_NAME, current);
                    final String providerName = PROVIDER_NAME.resolveModelAttribute(context, current).asString();
                    final Double providerVersion = asDoubleIfDefined(context, PROVIDER_VERSION, current);

                    final Predicate<Double> versionPredicate;
                    if (providerVersion != null) {
                       final Comparison comparison = Comparison.getComparison(VERSION_COMPARISON.resolveModelAttribute(context, current).asString());

                       versionPredicate = (Double d) -> comparison.getPredicate().test(d, providerVersion);
                    } else {
                        versionPredicate = null;
                    }

                    BiPredicate<String, Provider> thisPredicate = (String s, Provider p) -> {
                        return (mechanismName == null || mechanismName.equals(s))
                                && providerName.equals(p.getName())
                                && (providerVersion == null || versionPredicate.test(p.getVersion()));
                    };

                    predicate = predicate == null ? thisPredicate : predicate.or(thisPredicate);
                }


                boolean enabling = ENABLING.resolveModelAttribute(context, model).asBoolean();
                if (enabling == false) {
                    predicate = predicate.negate();
                }

                final BiPredicate<String, Provider> finalPredicate = predicate;
                final InjectedValue<SaslServerFactory> saslServerFactoryInjector = new InjectedValue<SaslServerFactory>();

                TrivialService<SaslServerFactory> saslServiceFactoryService = new TrivialService<SaslServerFactory>(() -> {
                    SaslServerFactory theFactory = saslServerFactoryInjector.getValue();
                    theFactory = new MechanismProviderFilteringSaslServerFactory(theFactory, finalPredicate);

                    return theFactory;
                });

                ServiceTarget serviceTarget = context.getServiceTarget();

                ServiceBuilder<SaslServerFactory> serviceBuilder = serviceTarget.addService(saslServerFactoryName, saslServiceFactoryService);

                serviceBuilder.addDependency(context.getCapabilityServiceName(
                        RuntimeCapability.buildDynamicCapabilityName(SASL_SERVER_FACTORY_CAPABILITY, saslServerFactory),
                        SaslServerFactory.class), SaslServerFactory.class, saslServerFactoryInjector);

                return serviceBuilder;
            }

        };

        return wrap(new SaslServerResourceDefinition(ElytronDescriptionConstants.MECHANISM_PROVIDER_FILTERING_SASL_SERVER_FACTORY, add, attributes), SaslServerDefinitions::getSaslServerFactory);
    }

    private static SaslServerFactory getSaslServerFactory(OperationContext context) throws OperationFailedException {
        RuntimeCapability<Void> runtimeCapability = SASL_SERVER_FACTORY_RUNTIME_CAPABILITY.fromBaseCapability(context.getCurrentAddressValue());
        ServiceName saslServerFactoryName = runtimeCapability.getCapabilityServiceName(SaslServerFactory.class);

        @SuppressWarnings("unchecked")
        ServiceController<SaslServerFactory> serviceContainer = (ServiceController<SaslServerFactory>) context.getServiceRegistry(false).getRequiredService(saslServerFactoryName);
        if (serviceContainer.getState() != State.UP) {
            return null;
        }
        return serviceContainer.getValue();
    }

    private static class SaslServerResourceDefinition extends SimpleResourceDefinition {

        private final String pathKey;
        private final AttributeDefinition[] attributes;

        SaslServerResourceDefinition(String pathKey, AbstractAddStepHandler add, AttributeDefinition ... attributes) {
            super(new Parameters(PathElement.pathElement(pathKey),
                    ElytronExtension.getResourceDescriptionResolver(pathKey))
                .setAddHandler(add)
                .setRemoveHandler(new RoleMapperRemoveHandler(add))
                .setAddRestartLevel(OperationEntry.Flag.RESTART_RESOURCE_SERVICES)
                .setRemoveRestartLevel(OperationEntry.Flag.RESTART_RESOURCE_SERVICES));
            this.pathKey = pathKey;
            this.attributes = attributes;
        }

        @Override
        public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
             if (attributes != null && attributes.length > 0) {
                 WriteAttributeHandler write = new WriteAttributeHandler(pathKey, attributes);
                 for (AttributeDefinition current : attributes) {
                     resourceRegistration.registerReadWriteAttribute(current, null, write);
                 }
             }
        }

    }

    private static class SaslServerAddHander extends AbstractAddStepHandler {


        private SaslServerAddHander(AttributeDefinition ... attributes) {
            super(SASL_SERVER_FACTORY_RUNTIME_CAPABILITY, attributes);
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model)
                throws OperationFailedException {
            RuntimeCapability<Void> runtimeCapability = SASL_SERVER_FACTORY_RUNTIME_CAPABILITY.fromBaseCapability(context.getCurrentAddressValue());
            ServiceName saslServerFactoryName = runtimeCapability.getCapabilityServiceName(SaslServerFactory.class);

            commonDependencies(installService(context, saslServerFactoryName, model))
                .setInitialMode(Mode.LAZY)
                .install();
        }

        protected ServiceBuilder<SaslServerFactory> installService(OperationContext context, ServiceName saslServerFactoryName, ModelNode model) throws OperationFailedException {
            ServiceTarget serviceTarget = context.getServiceTarget();
            TrivialService<SaslServerFactory> saslServerFactoryService = new TrivialService<SaslServerFactory>(getValueSupplier(context, model));

            return serviceTarget.addService(saslServerFactoryName, saslServerFactoryService);
        }

        protected ValueSupplier<SaslServerFactory> getValueSupplier(OperationContext context, ModelNode model) throws OperationFailedException {
            return () -> null;
        };

    }

    private static class WriteAttributeHandler extends RestartParentWriteAttributeHandler {

        WriteAttributeHandler(String parentName, AttributeDefinition ... attributes) {
            super(parentName, attributes);
        }

        @Override
        protected ServiceName getParentServiceName(PathAddress pathAddress) {
            return SASL_SERVER_FACTORY_RUNTIME_CAPABILITY.fromBaseCapability(pathAddress.getLastElement().getValue()).getCapabilityServiceName(SaslServerFactory.class);
        }
    }

    private static class RoleMapperRemoveHandler extends ServiceRemoveStepHandler {

        public RoleMapperRemoveHandler(AbstractAddStepHandler addOperation) {
            super(addOperation, SASL_SERVER_FACTORY_RUNTIME_CAPABILITY);
        }

        @Override
        protected ServiceName serviceName(String name) {
            return SASL_SERVER_FACTORY_RUNTIME_CAPABILITY.fromBaseCapability(name).getCapabilityServiceName(SaslServerFactory.class);
        }

    }

    private enum NamePredicate {

        HASH_MD5(SaslMechanismInformation.HASH_MD5),
        HASH_SHA(SaslMechanismInformation.HASH_SHA),
        HASH_SHA_256(SaslMechanismInformation.HASH_SHA_256),
        HASH_SHA_384(SaslMechanismInformation.HASH_SHA_384),
        HASH_SHA_512(SaslMechanismInformation.HASH_SHA_512),
        GS2(SaslMechanismInformation.GS2),
        SCRAM(SaslMechanismInformation.SCRAM),
        DIGEST(SaslMechanismInformation.DIGEST),
        IEC_ISO_9798(SaslMechanismInformation.IEC_ISO_9798),
        EAP(SaslMechanismInformation.EAP),
        MUTUAL(SaslMechanismInformation.MUTUAL),
        BINDING(SaslMechanismInformation.BINDING),
        RECOMMENDED(SaslMechanismInformation.RECOMMENDED);

        private final Predicate<String> predicate;

        private NamePredicate(Predicate<String> predicate) {
            this.predicate = predicate;
        }

        static String[] names() {
            NamePredicate[] namePredicates = NamePredicate.values();
            String[] names = new String[namePredicates.length];
            for (int i=0;i<namePredicates.length;i++) {
                names[i] = namePredicates.toString();
            }

            return names;
        }
    }

    private enum Comparison {

        LESS_THAN(ElytronDescriptionConstants.LESS_THAN, (Double left, Double right) ->  left < right),

        GREATER_THAN(ElytronDescriptionConstants.GREATER_THAN, (Double left, Double right) ->  left > right);

        private final String name;

        private final BiPredicate<Double, Double> predicate;

        private Comparison(final String name, final BiPredicate<Double, Double> predicate) {
            this.name = name;
            this.predicate = predicate;
        }

        BiPredicate<Double, Double> getPredicate() {
            return predicate;
        }

        @Override
        public String toString() {
            return name;
        }


        static Comparison getComparison(String value) {
            switch (value) {
                case ElytronDescriptionConstants.LESS_THAN:
                    return LESS_THAN;
                case ElytronDescriptionConstants.GREATER_THAN:
                    return GREATER_THAN;
            }

            throw new IllegalArgumentException("Invalid value");
        }
    }
}
