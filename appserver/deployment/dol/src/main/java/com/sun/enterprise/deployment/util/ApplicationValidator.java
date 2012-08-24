/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.deployment.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.*;
import java.util.logging.Level;

import com.sun.enterprise.deployment.*;
import com.sun.enterprise.deployment.types.EjbReference;
import com.sun.enterprise.deployment.types.MessageDestinationReferencer;
import com.sun.enterprise.util.LocalStringManagerImpl;
import org.glassfish.deployment.common.Descriptor;
import org.glassfish.deployment.common.DescriptorVisitor;
import org.glassfish.deployment.common.ModuleDescriptor;
import org.jvnet.hk2.annotations.Service;

/**
 * This class is responsible for validating the loaded DOL classes and 
 * transform some of the raw XML information into refined values used 
 * by the DOL runtime
 *
 * @author Jerome Dochez
 */
@Service(name="application_deploy")
public class ApplicationValidator extends ComponentValidator 
    implements ApplicationVisitor, ManagedBeanVisitor {


    // Used to store all descriptor details for validation purpose
    private HashMap<String, CommonResourceValidator> allResourceDescriptors = new HashMap<String, CommonResourceValidator>();

    // Used to store keys and descriptor names for validation purpose
    private HashMap<String, Vector> validNameSpaceDetails = new HashMap<String, Vector>();

    private final String APPCLIENT_KEYS = "APPCLIENT_KEYS";
    private final String EJBBUNDLE_KEYS = "EJBBUNDLE_KEYS";
    private final String APP_KEYS = "APP_KEYS";
    private final String WEBBUNDLE_KEYS = "WEBBUNDLE_KEYS";
    private final String EJB_KEYS = "EJB_KEYS";
    private final String CONNECTOR_KEYS = "CONNECTOR_KEYS";

    final String JNDI_COMP = "java:comp";
    final String JNDI_MODULE = "java:module";
    final String JNDI_APP = "java:app";

    private boolean allUniqueResource = true;

    String inValidJndiName = "";

    private static LocalStringManagerImpl localStrings =
            new LocalStringManagerImpl(ApplicationValidator.class);
    
    @Override
    public void accept (BundleDescriptor descriptor) {
        if (descriptor instanceof Application) {
            Application application = (Application)descriptor;
            accept(application);

            if (!validateResourceDescriptor(application)) {
                DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.application.fail",
                        new Object[] { application.getAppName() });
                throw new IllegalStateException(
                    localStrings.getLocalString("enterprise.deployment.util.application.fail",
                        "Application validation fails for given application {0} for jndi-name {1}",application.getAppName(),inValidJndiName));
            }

            for (BundleDescriptor ebd : application.getBundleDescriptorsOfType(DOLUtils.ejbType())) {
                ebd.visit(getSubDescriptorVisitor(ebd));
            }

            for (BundleDescriptor wbd : application.getBundleDescriptorsOfType(DOLUtils.warType())) {
                // This might be null in the case of an appclient
                // processing a client stubs .jar whose original .ear contained
                // a .war.  This will be fixed correctly in the deployment
                // stage but until then adding a non-null check will prevent
                // the validation step from bombing.
                if (wbd != null) {
                    wbd.visit(getSubDescriptorVisitor(wbd));
                }
            }

            for (BundleDescriptor cd :  application.getBundleDescriptorsOfType(DOLUtils.rarType())) {
                cd.visit(getSubDescriptorVisitor(cd));
            }

            for (BundleDescriptor acd : application.getBundleDescriptorsOfType(DOLUtils.carType())) {
                acd.visit(getSubDescriptorVisitor(acd));
            }

            // Visit all injectables first.  In some cases, basic type
            // information has to be derived from target inject method or
            // inject field.
            for(InjectionCapable injectable : application.getInjectableResources(application)) {
                accept(injectable);
            }

            super.accept(descriptor);
        } else {
            super.accept(descriptor);
        }
    }

    /**
     * visit an application object
     * @param application the application descriptor
     */
    @Override
    public void accept(Application application) {
        this.application = application;
        if (application.getBundleDescriptors().size() == 0) {
            throw new IllegalArgumentException("Application [" + 
                application.getRegistrationName() + 
                "] contains no valid components");
        }

        // now resolve any conflicted module names in the application

        // list to store the current conflicted modules
        List<ModuleDescriptor> conflicted = new ArrayList<ModuleDescriptor>();
        // make sure all the modules have unique names
        Set<ModuleDescriptor<BundleDescriptor>> modules =
            application.getModules();
        for (ModuleDescriptor module : modules) {
            // if this module is already added to the conflicted list
            // no need to process it again
            if (conflicted.contains(module)) {
                continue;
            }
            boolean foundConflictedModule = false;
            for (ModuleDescriptor module2 : modules) {
                // if this module is already added to the conflicted list
                // no need to process it again
                if (conflicted.contains(module2)) {
                    continue;
                }
                if ( !module.equals(module2) && 
                    module.getModuleName().equals(module2.getModuleName())) {
                    conflicted.add(module2);
                    foundConflictedModule = true;
                }
            }
            if (foundConflictedModule) {
                conflicted.add(module);
            }
        }

        // append the conflicted module names with their module type to 
        // make the names unique
        for (ModuleDescriptor cModule : conflicted) {
            cModule.setModuleName(cModule.getModuleName() + 
                cModule.getModuleType().toString());
        }
    }

//    FIXME by srini - add support in the new structure
    public void accept(EjbBundleDescriptor bundleDescriptor) {
        this.bundleDescriptor = bundleDescriptor;
        application = bundleDescriptor.getApplication();
        super.accept(bundleDescriptor);
        /** set the realm name on each ejb to match the ones on this application
         * this is required right now to pass the stringent CSIv2 criteria
         * whereby the realm-name for the ejb being authenticated on
         * has to match the one on the application. We look at the IORConfigurator
         * descriptor
         * @todo: change the csiv2 layer so that it does not look at
         * IORConfiguratorDescriptor.
         * @see iiop/security/SecurityMechanismSelector.evaluateClientConformance.
         */
        String rlm = application.getRealm();
        if (rlm != null) {
            for(EjbDescriptor ejb : bundleDescriptor.getEjbs()) {
                for (EjbIORConfigurationDescriptor desc : ejb.getIORConfigurationDescriptors()) {
                    desc.setRealmName(rlm);
                }
            }
        }
    }

    @Override
    public void accept(ManagedBeanDescriptor managedBean) {
        this.bundleDescriptor = managedBean.getBundle();
        this.application = bundleDescriptor.getApplication(); 

        for (Iterator itr = managedBean.getEjbReferenceDescriptors().iterator(); itr.hasNext();) {
            EjbReference aRef = (EjbReference) itr.next();
            accept(aRef);
        }

        for (Iterator it = managedBean.getResourceReferenceDescriptors().iterator(); it.hasNext();) {
            ResourceReferenceDescriptor next =
                    (ResourceReferenceDescriptor) it.next();
            accept(next);
        }

        for (Iterator it = managedBean.getResourceEnvReferenceDescriptors().iterator(); it.hasNext();) {
            ResourceEnvReferenceDescriptor next =
                    (ResourceEnvReferenceDescriptor) it.next();
            accept(next);
        }

        for (Iterator it = managedBean.getMessageDestinationReferenceDescriptors().iterator(); it.hasNext();) {
            MessageDestinationReferencer next =
                    (MessageDestinationReferencer) it.next();
            accept(next);
        }

        Set serviceRefs = managedBean.getServiceReferenceDescriptors();
        for (Iterator itr = serviceRefs.iterator(); itr.hasNext();) {
            accept((ServiceReferenceDescriptor) itr.next());
        }
    }

    @Override
    protected Collection<EjbDescriptor> getEjbDescriptors() {
        if (application!=null) 
            return application.getEjbDescriptors();
        return new HashSet<EjbDescriptor>();
    }

    @Override
    protected Application getApplication() {
        return application;
    }

    @Override
    protected BundleDescriptor getBundleDescriptor() {
        return bundleDescriptor;
    }

    @Override
    public DescriptorVisitor getSubDescriptorVisitor(Descriptor subDescriptor) {
        if (subDescriptor instanceof BundleDescriptor) {
            return ((BundleDescriptor)subDescriptor).getBundleVisitor();
        }
        return super.getSubDescriptorVisitor(subDescriptor);
    }

    /**
     * Method to read complete application and all defined descriptor for given app. Method is used to identify
     * scope and validation for all defined jndi names at different namespace.
     * @param application
     * @return
     */
    public boolean validateResourceDescriptor(Application application) {


        /*
        Below final String is the prefix which I am appending with each module name to avoid duplicates.
        In ejblite case application name, ejb bundle name and web bundle name always returns the same name if not
        specified. So my validation fails so to avoid the same appending difference prefix with each module name.

        For two ejb-jar.xml in two different modules as part of the ear, they must have unique module names
        (this is per spec requirement), so the module scoped resources just needs to be unique within their modules.
        So in that case all bundle name must be unique so appending extra string is not fail anything.

        It is used for internal reference only.
        */
        final String APP_LEVEL = "AppLevel:";
        final String EJBBUNDLE_LEVEL = "EBDLevel:";
        final String EJB_LEVEL = "EJBLevel:";
        final String APPCLIENTBUNDLE_LEVEL = "ACDevel:";
        final String APPCLIENT_LEVEL = "ACLevel:";
        final String WEBBUNDLE_LEVEL = "WBDLevel:";

        // Reads MSD and DSD at application level
        CommonResourceBundleDescriptor commonResourceBundleDescriptor = (CommonResourceBundleDescriptor) application;
        Vector appLevel = new Vector();
        if (commonResourceBundleDescriptor != null) {
            Set<MailSessionDescriptor> mailSessionDescriptors = commonResourceBundleDescriptor.getMailSessionDescriptors();
            if (isExistingMailSession(mailSessionDescriptors, APP_LEVEL+commonResourceBundleDescriptor.getName())) {
                return false;
            }
            Set<DataSourceDefinitionDescriptor> dataSourceDefinitionDescriptors = commonResourceBundleDescriptor.getDataSourceDefinitionDescriptors();
            if (isExistingDataSourceDefinition(dataSourceDefinitionDescriptors, APP_LEVEL+commonResourceBundleDescriptor.getName())) {
                return false;
            }
            Set<ConnectorResourceDefinitionDescriptor> connectorResourceDefinitionDescriptors = application.getConnectorResourceDefinitionDescriptors();
            if (isExistingConnectorResourceDefinitionDescriptor(connectorResourceDefinitionDescriptors, APP_LEVEL+application.getName())) {
                return false;
            }

            appLevel.add(APP_LEVEL+commonResourceBundleDescriptor.getName());
            validNameSpaceDetails.put(APP_KEYS, appLevel);
        }

        // Reads MSD and DSD at application-client level
        if (application != null) {
          Set<ApplicationClientDescriptor> appClientDescs = application.getBundleDescriptors(ApplicationClientDescriptor.class);
          Vector appClientLevel = new Vector();
          for (ApplicationClientDescriptor acd : appClientDescs) {
            Set<MailSessionDescriptor> mailSessionDescriptors = acd.getMailSessionDescriptors();
            if (isExistingMailSession(mailSessionDescriptors, APPCLIENTBUNDLE_LEVEL+acd.getName())) {
              return false;
            }
            Set<DataSourceDefinitionDescriptor> dataSourceDefinitionDescriptors = acd.getDataSourceDefinitionDescriptors();
            if (isExistingDataSourceDefinition(dataSourceDefinitionDescriptors, APPCLIENTBUNDLE_LEVEL+acd.getName())) {
              return false;
            }
            appClientLevel.add(APPCLIENTBUNDLE_LEVEL+acd.getName());
          }
          validNameSpaceDetails.put(APPCLIENT_KEYS, appClientLevel);
        }

        // Reads MSD and DSD at connector level
        if (application != null) {
          Set<ConnectorDescriptor> connectorDescs = application.getBundleDescriptors(ConnectorDescriptor.class);
          Vector cdLevel = new Vector();
          for (ConnectorDescriptor cd : connectorDescs) {
            Set<MailSessionDescriptor> mailSessionDescriptors = cd.getMailSessionDescriptors();
            if (isExistingMailSession(mailSessionDescriptors, APPCLIENT_LEVEL+cd.getName())) {
              return false;
            }
            Set<DataSourceDefinitionDescriptor> dataSourceDefinitionDescriptors = cd.getDataSourceDefinitionDescriptors();
            if (isExistingDataSourceDefinition(dataSourceDefinitionDescriptors, APPCLIENT_LEVEL+cd.getName())) {
              return false;
            }
            Set<ConnectorResourceDefinitionDescriptor> connectorResourceDefinitionDescriptors = cd.getConnectorResourceDefinitionDescriptors();
            if (isExistingConnectorResourceDefinitionDescriptor(connectorResourceDefinitionDescriptors, APPCLIENT_LEVEL+cd.getName())) {
                return false;
            }
            cdLevel.add(APPCLIENT_LEVEL+cd.getName());
          }
          validNameSpaceDetails.put(CONNECTOR_KEYS, cdLevel);
        }

        // Reads MSD and DSD at ejb-bundle level
        if (application != null) {
          Set<EjbBundleDescriptor> ejbBundleDescs = application.getBundleDescriptors(EjbBundleDescriptor.class);
          Vector ebdLevel = new Vector();
          Vector edLevel = new Vector();
          for (EjbBundleDescriptor ebd : ejbBundleDescs) {
            Set<MailSessionDescriptor> mailSessionDescriptors = ebd.getMailSessionDescriptors();
            if (isExistingMailSession(mailSessionDescriptors, EJBBUNDLE_LEVEL+ebd.getName())) {
              return false;
            }
            Set<DataSourceDefinitionDescriptor> dataSourceDefinitionDescriptors = ebd.getDataSourceDefinitionDescriptors();
            if (isExistingDataSourceDefinition(dataSourceDefinitionDescriptors, EJBBUNDLE_LEVEL+ebd.getName())) {
              return false;
            }
            Set<ConnectorResourceDefinitionDescriptor> connectorResourceDefinitionDescriptors = ebd.getConnectorResourceDefinitionDescriptors();
            if (isExistingConnectorResourceDefinitionDescriptor(connectorResourceDefinitionDescriptors, EJBBUNDLE_LEVEL+ebd.getName())) {
                return false;
            }
            ebdLevel.add(EJBBUNDLE_LEVEL+ebd.getName());


            // Reads MSD and DSD at ejb level
            Set<EjbDescriptor> ejbDescriptors = (Set<EjbDescriptor>) ebd.getEjbs();
            for (Iterator itr = ejbDescriptors.iterator(); itr.hasNext(); ) {
                EjbDescriptor ejbDescriptor = (EjbDescriptor) itr.next();
                mailSessionDescriptors = ejbDescriptor.getMailSessionDescriptors();
                if (isExistingMailSession(mailSessionDescriptors, EJB_LEVEL+ebd.getName() + "#" + ejbDescriptor.getName())) {
                    return false;
                }
                dataSourceDefinitionDescriptors = ejbDescriptor.getDataSourceDefinitionDescriptors();
                if (isExistingDataSourceDefinition(dataSourceDefinitionDescriptors, EJB_LEVEL+ebd.getName() + "#" + ejbDescriptor.getName())) {
                    return false;
                }
                connectorResourceDefinitionDescriptors = ejbDescriptor.getConnectorResourceDefinitionDescriptors();
                if (isExistingConnectorResourceDefinitionDescriptor(connectorResourceDefinitionDescriptors, EJB_LEVEL+ebd.getName() + "#" + ejbDescriptor.getName())) {
                    return false;
                }
              edLevel.add(EJB_LEVEL+ebd.getName() + "#" + ejbDescriptor.getName());

            }
          }
          validNameSpaceDetails.put(EJBBUNDLE_KEYS, ebdLevel);
          validNameSpaceDetails.put(EJB_KEYS, edLevel);
        }


        // Reads MSD and DSD at web-bundle level
        if (application != null) {
          Set<WebBundleDescriptor> webBundleDescs = application.getBundleDescriptors(WebBundleDescriptor.class);
          Vector wbdLevel = new Vector();
          for (WebBundleDescriptor wbd : webBundleDescs) {
            Set<MailSessionDescriptor> mailSessionDescriptors = wbd.getMailSessionDescriptors();
            if (isExistingMailSession(mailSessionDescriptors, WEBBUNDLE_LEVEL+wbd.getName())) {
              return false;
            }
            Set<DataSourceDefinitionDescriptor> dataSourceDefinitionDescriptors = wbd.getDataSourceDefinitionDescriptors();
            if (isExistingDataSourceDefinition(dataSourceDefinitionDescriptors, WEBBUNDLE_LEVEL+wbd.getName())) {
              return false;
            }
            Set<ConnectorResourceDefinitionDescriptor> connectorResourceDefinitionDescriptors = wbd.getConnectorResourceDefinitionDescriptors();
            if (isExistingConnectorResourceDefinitionDescriptor(connectorResourceDefinitionDescriptors, WEBBUNDLE_LEVEL+wbd.getName())) {
                return false;
            }
            wbdLevel.add(WEBBUNDLE_LEVEL+wbd.getName());
          }
          validNameSpaceDetails.put(WEBBUNDLE_KEYS, wbdLevel);
        }

        // if all resources names are unique then validate each descriptor is unique or not
        if (allUniqueResource) {
            return compareDescriptors();
        }

        return allUniqueResource;
    }

    /**
     * * Method to validate MSD is unique or not
     * @param mailSessionDescriptors
     * @param scope
     * @return
     */
    private boolean isExistingMailSession(Set<MailSessionDescriptor> mailSessionDescriptors, String scope) {
        for (Iterator itr = mailSessionDescriptors.iterator(); itr.hasNext(); ) {
            MailSessionDescriptor mailSessionDescriptor = (MailSessionDescriptor) itr.next();
            if (isExistsDescriptor(mailSessionDescriptor.getName(), mailSessionDescriptor, scope)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Method to validate DSD is unique or not
     * @param dataSourceDefinitionDescriptors
     * @param scope
     * @return
     */
    private boolean isExistingDataSourceDefinition(Set<DataSourceDefinitionDescriptor> dataSourceDefinitionDescriptors, String scope) {
        for (Iterator itr = dataSourceDefinitionDescriptors.iterator(); itr.hasNext(); ) {
            DataSourceDefinitionDescriptor dataSourceDefinitionDescriptor = (DataSourceDefinitionDescriptor) itr.next();
            if (isExistsDescriptor(dataSourceDefinitionDescriptor.getName(), dataSourceDefinitionDescriptor, scope)) {
                return true;
            }
        }
        return false;
    }

    /**
         * Method to validate CRD is unique or not
         * @param connectorResourceDefinitionDescriptors
         * @param scope
         * @return
         */
        private boolean isExistingConnectorResourceDefinitionDescriptor(Set<ConnectorResourceDefinitionDescriptor> connectorResourceDefinitionDescriptors, String scope) {
            for (Iterator itr = connectorResourceDefinitionDescriptors.iterator(); itr.hasNext(); ) {
                ConnectorResourceDefinitionDescriptor connectorResourceDefinitionDescriptor = (ConnectorResourceDefinitionDescriptor) itr.next();
                if (isExistsDescriptor(connectorResourceDefinitionDescriptor.getName(), connectorResourceDefinitionDescriptor, scope)) {
                    return true;
                }
            }
            return false;
        }

    /**
     * Method to compare existing descriptor with other descriptors. If both descriptor is equal then deployment
     * should be failed. scope is nothing but app level,connector level, ejb level etc., which is used later to
     * compare same jndi name is defined at different scope or not.
     * @param name
     * @param descriptor
     * @param scope
     * @return
     */
    private boolean isExistsDescriptor(String name, Descriptor descriptor, String scope) {

        if (descriptor != null) {

            CommonResourceValidator commonResourceValidator = allResourceDescriptors.get(name);
            if (commonResourceValidator != null) {
                Descriptor existingDescriptor = commonResourceValidator.getDescriptor();
                if (descriptor instanceof MailSessionDescriptor && existingDescriptor instanceof MailSessionDescriptor) {
                    if (!descriptor.equals(existingDescriptor)) {
                        allUniqueResource = false;
                        return true;
                    } else {
                        DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.descriptor.duplicate",
                                new Object[] { descriptor.getName() });

                    }
                } else if (descriptor instanceof DataSourceDefinitionDescriptor && existingDescriptor instanceof DataSourceDefinitionDescriptor) {
                    if (!descriptor.equals(existingDescriptor)) {
                        allUniqueResource = false;
                        return true;
                    } else {
                        DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.descriptor.duplicate",
                                new Object[] { descriptor.getName() });

                    }
                } else if (descriptor instanceof ConnectorResourceDefinitionDescriptor && existingDescriptor instanceof ConnectorResourceDefinitionDescriptor) {
                    if (!descriptor.equals(existingDescriptor)) {
                        allUniqueResource = false;
                        return true;
                    } else {
                        DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.descriptor.duplicate",
                                new Object[] { descriptor.getName() });

                    }
                }

                Vector vectorScope = commonResourceValidator.getScope();
                if (vectorScope != null) {
                    vectorScope.add(scope);
                }
                commonResourceValidator.setScope(vectorScope);
                allResourceDescriptors.put(name, commonResourceValidator);
            } else {
                Vector<String> vectorScope = new Vector<String>();
                vectorScope.add(scope);
                allResourceDescriptors.put(name, new CommonResourceValidator(descriptor, name, vectorScope));
            }
        }
        return false;
    }

    /**
     * Compare descriptor at given scope is valid and unique.
     * @return
     */
    private boolean compareDescriptors() {

        Vector appVectorName = validNameSpaceDetails.get(APP_KEYS);
        Vector ebdVectorName = validNameSpaceDetails.get(EJBBUNDLE_KEYS);

        for (String key : allResourceDescriptors.keySet()) {
            CommonResourceValidator commonResourceValidator = allResourceDescriptors.get(key);
            Vector scopeVector = commonResourceValidator.getScope();
            String jndiName = commonResourceValidator.getJndiName();

            if (jndiName.contains(JNDI_COMP)) {
                for (int i = 0; i < scopeVector.size(); i++) {
                    String scope = (String) scopeVector.get(i);
                    for (int j = 0; j < appVectorName.size(); j++) {
                        if (scope.equals(appVectorName.get(j))) {
                            inValidJndiName = jndiName;
                            DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.application.invalid.jndiname.scope",
                                new Object[] { jndiName });
                            return false;
                        }
                    }
                    for (int j = 0; j < ebdVectorName.size(); j++) {
                        if (scope.equals(ebdVectorName.get(j))) {
                            inValidJndiName = jndiName;
                            DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.application.invalid.jndiname.scope",
                                new Object[] { jndiName });
                            return false;
                        }
                    }
                }
            }

            if (jndiName.contains(JNDI_MODULE)) {
                for (int i = 0; i < scopeVector.size(); i++) {
                    String scope = (String) scopeVector.get(i);
                    for (int j = 0; j < appVectorName.size(); j++) {
                        if (scope.equals(appVectorName.get(j))) {
                            inValidJndiName = jndiName;
                            DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.application.invalid.jndiname.scope",
                                new Object[] { jndiName });
                            return false;
                        }
                    }
                }
            }

            if (scopeVector.size() > 1) {

                if (jndiName.contains(JNDI_COMP)) {
                    if (!compareVectorForComp(scopeVector,jndiName)) {
                        return false;
                    }
                } else if (jndiName.contains(JNDI_MODULE)) {
                    if (!compareVectorForModule(scopeVector,jndiName)) {
                        return false;
                    }
                } else if (jndiName.contains(JNDI_APP)) {
                    if (!compareVectorForApp(scopeVector,jndiName)) {
                        return false;
                    }
                } else {
                    try {
                        InitialContext ic = new InitialContext();
                        Object lookup = ic.lookup(jndiName);
                        if (lookup != null) {
                            return false;
                        }
                    } catch (NamingException e) {
                        inValidJndiName = jndiName;
                        DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.application.lookup",
                                new Object[] { jndiName });
                    }

                }

            }
        }
        return true;
    }

    /**
     * Method to validate jndi name for app namespace
     * @param myVector
     * @return
     */
    private boolean compareVectorForApp(Vector myVector,String jndiName) {

        for (int j = 0; j < myVector.size(); j++) {
            String firstElement = (String) myVector.get(j);
            if (firstElement.contains("#")) {
                firstElement = firstElement.substring(0, firstElement.indexOf("#"));
            }
            for (int i = j+1; i < myVector.size(); i++) {
                String otherElements = (String) myVector.get(i);
                if (otherElements.contains("#")) {
                    otherElements = otherElements.substring(0, otherElements.indexOf("#"));
                }
                if (firstElement.equals(otherElements)) {
                    inValidJndiName = jndiName;
                    DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.application.invalid.namespace",
                        new Object[] { jndiName, application.getAppName() });
                }
            }
        }
        return true;
    }

    /**
     * Method to validate jndi name for module namespace
     * @param myVector
     * @return
     */
    private boolean compareVectorForModule(Vector myVector,String jndiName) {

        if (!compareVectorForApp(myVector,jndiName)) {
            return false;
        }

        for (int j = 0; j < myVector.size(); j++) {
            String firstElement = (String) myVector.firstElement();
            if (firstElement.contains("#")) {
                firstElement = firstElement.substring(firstElement.indexOf("#") + 1, firstElement.length());
            }
            if (firstElement.contains("#")) {
                firstElement = firstElement.substring(0, firstElement.indexOf("#"));
            }
            for (int i = j+1; i < myVector.size(); i++) {
                String otherElements = (String) myVector.get(i);
                if (otherElements.contains("#")) {
                    otherElements = otherElements.substring(otherElements.indexOf("#") + 1, otherElements.length());
                }
                if (otherElements.contains("#")) {
                    otherElements = otherElements.substring(0, otherElements.indexOf("#"));
                }
                if (firstElement.equals(otherElements)) {
                    inValidJndiName = jndiName;
                    DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.application.invalid.namespace",
                        new Object[] { jndiName, application.getAppName() });
                }
            }
        }
        return true;
    }

    /**
     * Method to validate jndi name for comp namespace
     * @param myVector
     * @return
     */
    private boolean compareVectorForComp(Vector myVector,String jndiName) {

        if (!compareVectorForModule(myVector,jndiName)) {
            return false;
        }

        for (int j = 0; j < myVector.size(); j++) {
            String firstElement = (String) myVector.firstElement();
            if (firstElement.contains("#")) {
                firstElement = firstElement.substring(firstElement.lastIndexOf("#") + 1, firstElement.length());
            }
            if (firstElement.contains("#")) {
                firstElement = firstElement.substring(firstElement.lastIndexOf("#") + 1, firstElement.length());
            }
            for (int i = j+1; i < myVector.size(); i++) {
                String otherElements = (String) myVector.get(i);
                if (otherElements.contains("#")) {
                    otherElements = otherElements.substring(otherElements.lastIndexOf("#") + 1, otherElements.length());
                }
                if (otherElements.contains("#")) {
                    otherElements = otherElements.substring(otherElements.lastIndexOf("#") + 1, otherElements.length());
                }
                if (firstElement.equals(otherElements)) {
                    inValidJndiName = jndiName;
                    DOLUtils.getDefaultLogger().log(Level.SEVERE, "enterprise.deployment.util.application.invalid.namespace",
                        new Object[] { jndiName, application.getAppName() });
                    return false;
                }
            }
        }
        return true;
    }
}
