/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.catalina.core;


import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.naming.directory.DirContext;
import javax.servlet.ServletException;

import org.apache.catalina.CatalinaFactory;
import org.apache.catalina.Cluster;
import org.apache.catalina.Container;
import org.apache.catalina.ContainerEvent;
import org.apache.catalina.ContainerListener;
import org.apache.catalina.Globals;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Realm;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.LifecycleBase;
import org.apache.tomcat.util.res.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.naming.resources.ProxyDirContext;
import org.apache.tomcat.util.modeler.Registry;


/**
 * Abstract implementation of the <b>Container</b> interface, providing common
 * functionality required by nearly every implementation.  Classes extending
 * this base class must implement <code>getInfo()</code>, and may implement
 * a replacement for <code>invoke()</code>.
 * <p>
 * All subclasses of this abstract base class will include support for a
 * Pipeline object that defines the processing to be performed for each request
 * received by the <code>invoke()</code> method of this class, utilizing the
 * "Chain of Responsibility" design pattern.  A subclass should encapsulate its
 * own processing functionality as a <code>Valve</code>, and configure this
 * Valve into the pipeline by calling <code>setBasic()</code>.
 * <p>
 * This implementation fires property change events, per the JavaBeans design
 * pattern, for changes in singleton properties.  In addition, it fires the
 * following <code>ContainerEvent</code> events to listeners who register
 * themselves with <code>addContainerListener()</code>:
 * <table border=1>
 *   <tr>
 *     <th>Type</th>
 *     <th>Data</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td align=center><code>addChild</code></td>
 *     <td align=center><code>Container</code></td>
 *     <td>Child container added to this Container.</td>
 *   </tr>
 *   <tr>
 *     <td align=center><code>addValve</code></td>
 *     <td align=center><code>Valve</code></td>
 *     <td>Valve added to this Container.</td>
 *   </tr>
 *   <tr>
 *     <td align=center><code>removeChild</code></td>
 *     <td align=center><code>Container</code></td>
 *     <td>Child container removed from this Container.</td>
 *   </tr>
 *   <tr>
 *     <td align=center><code>removeValve</code></td>
 *     <td align=center><code>Valve</code></td>
 *     <td>Valve removed from this Container.</td>
 *   </tr>
 *   <tr>
 *     <td align=center><code>start</code></td>
 *     <td align=center><code>null</code></td>
 *     <td>Container was started.</td>
 *   </tr>
 *   <tr>
 *     <td align=center><code>stop</code></td>
 *     <td align=center><code>null</code></td>
 *     <td>Container was stopped.</td>
 *   </tr>
 * </table>
 * Subclasses that fire additional events should document them in the
 * class comments of the implementation class.
 * 
 * TODO: Review synchronisation around background processing. See bug 47024. 
 * 
 * @author Craig R. McClanahan
 */

public abstract class ContainerBase extends LifecycleBase
    implements Container, MBeanRegistration {

    private static final org.apache.juli.logging.Log log=
        org.apache.juli.logging.LogFactory.getLog( ContainerBase.class );

    /**
     * Perform addChild with the permissions of this class.
     * addChild can be called with the XML parser on the stack,
     * this allows the XML parser to have fewer privileges than
     * Tomcat.
     */
    protected class PrivilegedAddChild
        implements PrivilegedAction<Void> {

        private Container child;

        PrivilegedAddChild(Container child) {
            this.child = child;
        }

        public Void run() {
            addChildInternal(child);
            return null;
        }

    }


    // ----------------------------------------------------- Instance Variables


    /**
     * The child Containers belonging to this Container, keyed by name.
     */
    protected HashMap<String, Container> children =
        new HashMap<String, Container>();


    /**
     * The processor delay for this component.
     */
    protected int backgroundProcessorDelay = -1;


    /**
     * The container event listeners for this Container.
     */
    protected ArrayList<ContainerListener> listeners = new ArrayList<ContainerListener>();


    /**
     * The Loader implementation with which this Container is associated.
     */
    protected Loader loader = null;


    /**
     * The Logger implementation with which this Container is associated.
     */
    protected Log logger = null;


    /**
     * Associated logger name.
     */
    protected String logName = null;
    

    /**
     * The Manager implementation with which this Container is associated.
     */
    protected Manager manager = null;


    /**
     * The cluster with which this Container is associated.
     */
    protected Cluster cluster = null;

    
    /**
     * The human-readable name of this Container.
     */
    protected String name = null;


    /**
     * The parent Container to which this Container is a child.
     */
    protected Container parent = null;


    /**
     * The parent class loader to be configured when we install a Loader.
     */
    protected ClassLoader parentClassLoader = null;


    /**
     * The Pipeline object with which this Container is associated.
     */
    protected Pipeline pipeline =
        CatalinaFactory.getFactory().createPipeline(this);


    /**
     * The Realm with which this Container is associated.
     */
    protected Realm realm = null;


    /**
     * The resources DirContext object with which this Container is associated.
     */
    protected DirContext resources = null;


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);


    /**
     * Will children be started automatically when they are added.
     */
    protected boolean startChildren = true;

    /**
     * The property change support for this component.
     */
    protected PropertyChangeSupport support = new PropertyChangeSupport(this);


    /**
     * The background thread.
     */
    private Thread thread = null;


    /**
     * The background thread completion semaphore.
     */
    private volatile boolean threadDone = false;


    // ------------------------------------------------------------- Properties


    /**
     * Get the delay between the invocation of the backgroundProcess method on
     * this container and its children. Child containers will not be invoked
     * if their delay value is not negative (which would mean they are using 
     * their own thread). Setting this to a positive value will cause 
     * a thread to be spawn. After waiting the specified amount of time, 
     * the thread will invoke the executePeriodic method on this container 
     * and all its children.
     */
    public int getBackgroundProcessorDelay() {
        return backgroundProcessorDelay;
    }


    /**
     * Set the delay between the invocation of the execute method on this
     * container and its children.
     * 
     * @param delay The delay in seconds between the invocation of 
     *              backgroundProcess methods
     */
    public void setBackgroundProcessorDelay(int delay) {
        backgroundProcessorDelay = delay;
    }


    /**
     * Return descriptive information about this Container implementation and
     * the corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    public String getInfo() {
        return this.getClass().getName();
    }


    /**
     * Return the Loader with which this Container is associated.  If there is
     * no associated Loader, return the Loader associated with our parent
     * Container (if any); otherwise, return <code>null</code>.
     */
    public Loader getLoader() {

        if (loader != null)
            return (loader);
        if (parent != null)
            return (parent.getLoader());
        return (null);

    }


    /**
     * Set the Loader with which this Container is associated.
     *
     * @param loader The newly associated loader
     */
    public synchronized void setLoader(Loader loader) {

        // Change components if necessary
        Loader oldLoader = this.loader;
        if (oldLoader == loader)
            return;
        this.loader = loader;

        // Stop the old component if necessary
        if (getState().isAvailable() && (oldLoader != null) &&
            (oldLoader instanceof Lifecycle)) {
            try {
                ((Lifecycle) oldLoader).stop();
            } catch (LifecycleException e) {
                log.error("ContainerBase.setLoader: stop: ", e);
            }
        }

        // Start the new component if necessary
        if (loader != null)
            loader.setContainer(this);
        if (getState().isAvailable() && (loader != null) &&
            (loader instanceof Lifecycle)) {
            try {
                ((Lifecycle) loader).start();
            } catch (LifecycleException e) {
                log.error("ContainerBase.setLoader: start: ", e);
            }
        }

        // Report this property change to interested listeners
        support.firePropertyChange("loader", oldLoader, this.loader);

    }


    /**
     * Return the Logger for this Container.
     */
    public Log getLogger() {

        if (logger != null)
            return (logger);
        logger = LogFactory.getLog(logName());
        return (logger);

    }


    /**
     * Return the Manager with which this Container is associated.  If there is
     * no associated Manager, return the Manager associated with our parent
     * Container (if any); otherwise return <code>null</code>.
     */
    public Manager getManager() {

        if (manager != null)
            return (manager);
        if (parent != null)
            return (parent.getManager());
        return (null);

    }


    /**
     * Set the Manager with which this Container is associated.
     *
     * @param manager The newly associated Manager
     */
    public synchronized void setManager(Manager manager) {

        // Change components if necessary
        Manager oldManager = this.manager;
        if (oldManager == manager)
            return;
        this.manager = manager;

        // Stop the old component if necessary
        if (getState().isAvailable() && (oldManager != null) &&
            (oldManager instanceof Lifecycle)) {
            try {
                ((Lifecycle) oldManager).stop();
            } catch (LifecycleException e) {
                log.error("ContainerBase.setManager: stop: ", e);
            }
        }

        // Start the new component if necessary
        if (manager != null)
            manager.setContainer(this);
        if (getState().isAvailable() && (manager != null) &&
            (manager instanceof Lifecycle)) {
            try {
                ((Lifecycle) manager).start();
            } catch (LifecycleException e) {
                log.error("ContainerBase.setManager: start: ", e);
            }
        }

        // Report this property change to interested listeners
        support.firePropertyChange("manager", oldManager, this.manager);

    }


    /**
     * Return an object which may be utilized for mapping to this component.
     */
    public Object getMappingObject() {
        return this;
    }


    /**
     * Return the Cluster with which this Container is associated.  If there is
     * no associated Cluster, return the Cluster associated with our parent
     * Container (if any); otherwise return <code>null</code>.
     */
    public Cluster getCluster() {
        if (cluster != null)
            return (cluster);

        if (parent != null)
            return (parent.getCluster());

        return (null);
    }


    /**
     * Set the Cluster with which this Container is associated.
     *
     * @param cluster The newly associated Cluster
     */
    public synchronized void setCluster(Cluster cluster) {
        // Change components if necessary
        Cluster oldCluster = this.cluster;
        if (oldCluster == cluster)
            return;
        this.cluster = cluster;

        // Stop the old component if necessary
        if (getState().isAvailable() && (oldCluster != null) &&
            (oldCluster instanceof Lifecycle)) {
            try {
                ((Lifecycle) oldCluster).stop();
            } catch (LifecycleException e) {
                log.error("ContainerBase.setCluster: stop: ", e);
            }
        }

        // Start the new component if necessary
        if (cluster != null)
            cluster.setContainer(this);

        if (getState().isAvailable() && (cluster != null) &&
            (cluster instanceof Lifecycle)) {
            try {
                ((Lifecycle) cluster).start();
            } catch (LifecycleException e) {
                log.error("ContainerBase.setCluster: start: ", e);
            }
        }

        // Report this property change to interested listeners
        support.firePropertyChange("cluster", oldCluster, this.cluster);
    }


    /**
     * Return a name string (suitable for use by humans) that describes this
     * Container.  Within the set of child containers belonging to a particular
     * parent, Container names must be unique.
     */
    public String getName() {

        return (name);

    }


    /**
     * Set a name string (suitable for use by humans) that describes this
     * Container.  Within the set of child containers belonging to a particular
     * parent, Container names must be unique.
     *
     * @param name New name of this container
     *
     * @exception IllegalStateException if this Container has already been
     *  added to the children of a parent Container (after which the name
     *  may not be changed)
     */
    public void setName(String name) {

        String oldName = this.name;
        this.name = name;
        support.firePropertyChange("name", oldName, this.name);
    }


    /**
     * Return if children of this container will be started automatically when
     * they are added to this container.
     */
    public boolean getStartChildren() {

        return (startChildren);

    }


    /**
     * Set if children of this container will be started automatically when
     * they are added to this container.
     *
     * @param startChildren New value of the startChildren flag
     */
    public void setStartChildren(boolean startChildren) {

        boolean oldStartChildren = this.startChildren;
        this.startChildren = startChildren;
        support.firePropertyChange("startChildren", oldStartChildren, this.startChildren);
    }


    /**
     * Return the Container for which this Container is a child, if there is
     * one.  If there is no defined parent, return <code>null</code>.
     */
    public Container getParent() {

        return (parent);

    }


    /**
     * Set the parent Container to which this Container is being added as a
     * child.  This Container may refuse to become attached to the specified
     * Container by throwing an exception.
     *
     * @param container Container to which this Container is being added
     *  as a child
     *
     * @exception IllegalArgumentException if this Container refuses to become
     *  attached to the specified Container
     */
    public void setParent(Container container) {

        Container oldParent = this.parent;
        this.parent = container;
        support.firePropertyChange("parent", oldParent, this.parent);

    }


    /**
     * Return the parent class loader (if any) for this web application.
     * This call is meaningful only <strong>after</strong> a Loader has
     * been configured.
     */
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null)
            return (parentClassLoader);
        if (parent != null) {
            return (parent.getParentClassLoader());
        }
        return (ClassLoader.getSystemClassLoader());

    }


    /**
     * Set the parent class loader (if any) for this web application.
     * This call is meaningful only <strong>before</strong> a Loader has
     * been configured, and the specified value (if non-null) should be
     * passed as an argument to the class loader constructor.
     *
     *
     * @param parent The new parent class loader
     */
    public void setParentClassLoader(ClassLoader parent) {
        ClassLoader oldParentClassLoader = this.parentClassLoader;
        this.parentClassLoader = parent;
        support.firePropertyChange("parentClassLoader", oldParentClassLoader,
                                   this.parentClassLoader);

    }


    /**
     * Return the Pipeline object that manages the Valves associated with
     * this Container.
     */
    public Pipeline getPipeline() {

        return (this.pipeline);

    }


    /**
     * Return the Realm with which this Container is associated.  If there is
     * no associated Realm, return the Realm associated with our parent
     * Container (if any); otherwise return <code>null</code>.
     */
    public Realm getRealm() {

        if (realm != null)
            return (realm);
        if (parent != null)
            return (parent.getRealm());
        return (null);

    }


    /**
     * Set the Realm with which this Container is associated.
     *
     * @param realm The newly associated Realm
     */
    public synchronized void setRealm(Realm realm) {

        // Change components if necessary
        Realm oldRealm = this.realm;
        if (oldRealm == realm)
            return;
        this.realm = realm;

        // Stop the old component if necessary
        if (getState().isAvailable() && (oldRealm != null) &&
            (oldRealm instanceof Lifecycle)) {
            try {
                ((Lifecycle) oldRealm).stop();
            } catch (LifecycleException e) {
                log.error("ContainerBase.setRealm: stop: ", e);
            }
        }

        // Start the new component if necessary
        if (realm != null)
            realm.setContainer(this);
        if (getState().isAvailable() && (realm != null) &&
            (realm instanceof Lifecycle)) {
            try {
                ((Lifecycle) realm).start();
            } catch (LifecycleException e) {
                log.error("ContainerBase.setRealm: start: ", e);
            }
        }

        // Report this property change to interested listeners
        support.firePropertyChange("realm", oldRealm, this.realm);

    }


    /**
      * Return the resources DirContext object with which this Container is
      * associated.  If there is no associated resources object, return the
      * resources associated with our parent Container (if any); otherwise
      * return <code>null</code>.
     */
    public DirContext getResources() {
        if (resources != null)
            return (resources);
        if (parent != null)
            return (parent.getResources());
        return (null);

    }


    /**
     * Set the resources DirContext object with which this Container is
     * associated.
     *
     * @param resources The newly associated DirContext
     */
    public synchronized void setResources(DirContext resources) {
        // Called from StandardContext.setResources()
        //              <- StandardContext.start() 
        //              <- ContainerBase.addChildInternal() 

        // Change components if necessary
        DirContext oldResources = this.resources;
        if (oldResources == resources)
            return;
        Hashtable<String, String> env = new Hashtable<String, String>();
        if (getParent() != null)
            env.put(ProxyDirContext.HOST, getParent().getName());
        env.put(ProxyDirContext.CONTEXT, getName());
        this.resources = new ProxyDirContext(env, resources);
        // Report this property change to interested listeners
        support.firePropertyChange("resources", oldResources, this.resources);

    }


    // ------------------------------------------------------ Container Methods


    /**
     * Add a new child Container to those associated with this Container,
     * if supported.  Prior to adding this Container to the set of children,
     * the child's <code>setParent()</code> method must be called, with this
     * Container as an argument.  This method may thrown an
     * <code>IllegalArgumentException</code> if this Container chooses not
     * to be attached to the specified Container, in which case it is not added
     *
     * @param child New child Container to be added
     *
     * @exception IllegalArgumentException if this exception is thrown by
     *  the <code>setParent()</code> method of the child Container
     * @exception IllegalArgumentException if the new child does not have
     *  a name unique from that of existing children of this Container
     * @exception IllegalStateException if this Container does not support
     *  child Containers
     */
    public void addChild(Container child) {
        if (Globals.IS_SECURITY_ENABLED) {
            PrivilegedAction<Void> dp =
                new PrivilegedAddChild(child);
            AccessController.doPrivileged(dp);
        } else {
            addChildInternal(child);
        }
    }

    private void addChildInternal(Container child) {

        if( log.isDebugEnabled() )
            log.debug("Add child " + child + " " + this);
        synchronized(children) {
            if (children.get(child.getName()) != null)
                throw new IllegalArgumentException("addChild:  Child name '" +
                                                   child.getName() +
                                                   "' is not unique");
            child.setParent(this);  // May throw IAE
            children.put(child.getName(), child);

            // Start child
            if (getState().isAvailable() && startChildren) {
                boolean success = false;
                try {
                    child.start();
                    success = true;
                } catch (LifecycleException e) {
                    log.error("ContainerBase.addChild: start: ", e);
                    throw new IllegalStateException
                        ("ContainerBase.addChild: start: " + e);
                } finally {
                    if (!success) {
                        children.remove(child.getName());
                    }
                }
            }

            fireContainerEvent(ADD_CHILD_EVENT, child);
        }

    }


    /**
     * Add a container event listener to this component.
     *
     * @param listener The listener to add
     */
    public void addContainerListener(ContainerListener listener) {

        synchronized (listeners) {
            listeners.add(listener);
        }

    }


    /**
     * Add a property change listener to this component.
     *
     * @param listener The listener to add
     */
    public void addPropertyChangeListener(PropertyChangeListener listener) {

        support.addPropertyChangeListener(listener);

    }


    /**
     * Return the child Container, associated with this Container, with
     * the specified name (if any); otherwise, return <code>null</code>
     *
     * @param name Name of the child Container to be retrieved
     */
    public Container findChild(String name) {

        if (name == null)
            return (null);
        synchronized (children) {       // Required by post-start changes
            return children.get(name);
        }

    }


    /**
     * Return the set of children Containers associated with this Container.
     * If this Container has no children, a zero-length array is returned.
     */
    public Container[] findChildren() {

        synchronized (children) {
            Container results[] = new Container[children.size()];
            return children.values().toArray(results);
        }

    }


    /**
     * Return the set of container listeners associated with this Container.
     * If this Container has no registered container listeners, a zero-length
     * array is returned.
     */
    public ContainerListener[] findContainerListeners() {

        synchronized (listeners) {
            ContainerListener[] results = 
                new ContainerListener[listeners.size()];
            return listeners.toArray(results);
        }

    }


    /**
     * Process the specified Request, to produce the corresponding Response,
     * by invoking the first Valve in our pipeline (if any), or the basic
     * Valve otherwise.
     *
     * @param request Request to be processed
     * @param response Response to be produced
     *
     * @exception IllegalStateException if neither a pipeline or a basic
     *  Valve have been configured for this Container
     * @exception IOException if an input/output error occurred while
     *  processing
     * @exception ServletException if a ServletException was thrown
     *  while processing this request
     */
    public void invoke(Request request, Response response)
        throws IOException, ServletException {

        pipeline.getFirst().invoke(request, response);

    }


    /**
     * Remove an existing child Container from association with this parent
     * Container.
     *
     * @param child Existing child Container to be removed
     */
    public void removeChild(Container child) {

        synchronized(children) {
            if (children.get(child.getName()) == null)
                return;
            children.remove(child.getName());
        }
        
        if (getState().isAvailable()) {
            try {
                if (child.getState().isAvailable()) {
                    child.stop();
                }
            } catch (LifecycleException e) {
                log.error("ContainerBase.removeChild: stop: ", e);
            }
        }
        
        fireContainerEvent(REMOVE_CHILD_EVENT, child);
        
        // child.setParent(null);

    }


    /**
     * Remove a container event listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removeContainerListener(ContainerListener listener) {

        synchronized (listeners) {
            listeners.remove(listener);
        }

    }


    /**
     * Remove a property change listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removePropertyChangeListener(PropertyChangeListener listener) {

        support.removePropertyChangeListener(listener);

    }


    /**
     * Start this component and implement the requirements
     * of {@link LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {

        // Start our subordinate components, if any
        if ((loader != null) && (loader instanceof Lifecycle))
            ((Lifecycle) loader).start();
        logger = null;
        getLogger();
        if ((logger != null) && (logger instanceof Lifecycle))
            ((Lifecycle) logger).start();
        if ((manager != null) && (manager instanceof Lifecycle))
            ((Lifecycle) manager).start();
        if ((cluster != null) && (cluster instanceof Lifecycle))
            ((Lifecycle) cluster).start();
        if ((realm != null) && (realm instanceof Lifecycle))
            ((Lifecycle) realm).start();
        if ((resources != null) && (resources instanceof Lifecycle))
            ((Lifecycle) resources).start();

        // Start our child containers, if any
        Container children[] = findChildren();
        for (int i = 0; i < children.length; i++) {
            children[i].start();
        }

        // Start the Valves in our pipeline (including the basic), if any
        if (pipeline instanceof Lifecycle)
            ((Lifecycle) pipeline).start();


        setState(LifecycleState.STARTING);

        // Start our thread
        threadStart();

    }


    /**
     * Stop this component and implement the requirements
     * of {@link LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {

        // Stop our thread
        threadStop();

        setState(LifecycleState.STOPPING);

        // Stop the Valves in our pipeline (including the basic), if any
        if (pipeline instanceof Lifecycle) {
            ((Lifecycle) pipeline).stop();
        }

        // Stop our child containers, if any
        Container children[] = findChildren();
        for (int i = 0; i < children.length; i++) {
            children[i].stop();
        }
        // Remove children - so next start can work
        children = findChildren();
        for (int i = 0; i < children.length; i++) {
            removeChild(children[i]);
        }

        // Stop our subordinate components, if any
        if ((resources != null) && (resources instanceof Lifecycle)) {
            ((Lifecycle) resources).stop();
        }
        if ((realm != null) && (realm instanceof Lifecycle)) {
            ((Lifecycle) realm).stop();
        }
        if ((cluster != null) && (cluster instanceof Lifecycle)) {
            ((Lifecycle) cluster).stop();
        }
        if ((manager != null) && (manager instanceof Lifecycle)) {
            ((Lifecycle) manager).stop();
        }
        if ((logger != null) && (logger instanceof Lifecycle)) {
            ((Lifecycle) logger).stop();
        }
        if ((loader != null) && (loader instanceof Lifecycle)) {
            ((Lifecycle) loader).stop();
        }
    }

    /** Init method, part of the MBean lifecycle.
     *  If the container was added via JMX, it'll register itself with the 
     * parent, using the ObjectName conventions to locate the parent.
     * 
     *  If the container was added directly and it doesn't have an ObjectName,
     * it'll create a name and register itself with the JMX console. On destroy(), 
     * the object will unregister.
     * 
     * @throws Exception
     */ 
    @Override
    protected void initInternal() throws LifecycleException{

        try {
            if( this.getParent() == null ) {
                // "Life" update
                ObjectName parentName;
                    parentName = getParentName();
    
                //log.info("Register " + parentName );
                if( parentName != null && 
                        mserver.isRegistered(parentName)) 
                {
                    mserver.invoke(parentName, "addChild", new Object[] { this },
                            new String[] {"org.apache.catalina.Container"});
                }
            }
        } catch (MalformedObjectNameException e) {
            throw new LifecycleException(e);
        } catch (InstanceNotFoundException e) {
            throw new LifecycleException(e);
        } catch (ReflectionException e) {
            throw new LifecycleException(e);
        } catch (MBeanException e) {
            throw new LifecycleException(e);
        }
    }
    
    public ObjectName getParentName() throws MalformedObjectNameException {
        return null;
    }
    
    @Override
    protected void destroyInternal() throws LifecycleException {

        // unregister this component
        if ( oname != null ) {
            try {
                if( controller == oname ) {
                    Registry.getRegistry(null, null)
                        .unregisterComponent(oname);
                    if(log.isDebugEnabled())
                        log.debug("unregistering " + oname);
                }
            } catch( Throwable t ) {
                log.error("Error unregistering ", t );
            }
        }

        if (parent != null) {
            parent.removeChild(this);
        }

    }

    // ------------------------------------------------------- Pipeline Methods


    /**
     * Convenience method, intended for use by the digester to simplify the
     * process of adding Valves to containers. See
     * {@link Pipeline#addValve(Valve)} for full details. Components other than
     * the digester should use {@link #getPipeline()}.{@link #addValve(Valve)} in case a
     * future implementation provides an alternative method for the digester to
     * use.
     *
     * @param valve Valve to be added
     *
     * @exception IllegalArgumentException if this Container refused to
     *  accept the specified Valve
     * @exception IllegalArgumentException if the specified Valve refuses to be
     *  associated with this Container
     * @exception IllegalStateException if the specified Valve is already
     *  associated with a different Container
     */
    public synchronized void addValve(Valve valve) {

        pipeline.addValve(valve);
    }


    /**
     * Execute a periodic task, such as reloading, etc. This method will be
     * invoked inside the classloading context of this container. Unexpected
     * throwables will be caught and logged.
     */
    public void backgroundProcess() {
        
        if (!getState().isAvailable())
            return;

        if (cluster != null) {
            try {
                cluster.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.cluster", cluster), e);                
            }
        }
        if (loader != null) {
            try {
                loader.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.loader", loader), e);                
            }
        }
        if (manager != null) {
            try {
                manager.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.manager", manager), e);                
            }
        }
        if (realm != null) {
            try {
                realm.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.realm", realm), e);                
            }
        }
        Valve current = pipeline.getFirst();
        while (current != null) {
            try {
                current.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.valve", current), e);                
            }
            current = current.getNext();
        }
        fireLifecycleEvent(Lifecycle.PERIODIC_EVENT, null);
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Notify all container event listeners that a particular event has
     * occurred for this Container.  The default implementation performs
     * this notification synchronously using the calling thread.
     *
     * @param type Event type
     * @param data Event data
     */
    public void fireContainerEvent(String type, Object data) {

        if (listeners.size() < 1)
            return;
        ContainerEvent event = new ContainerEvent(this, type, data);
        ContainerListener list[] = new ContainerListener[0];
        synchronized (listeners) {
            list = listeners.toArray(list);
        }
        for (int i = 0; i < list.length; i++)
            list[i].containerEvent(event);

    }


    /**
     * Return the abbreviated name of this container for logging messages
     */
    protected String logName() {

        if (logName != null) {
            return logName;
        }
        String loggerName = null;
        Container current = this;
        while (current != null) {
            String name = current.getName();
            if ((name == null) || (name.equals(""))) {
                name = "/";
            }
            loggerName = "[" + name + "]" 
                + ((loggerName != null) ? ("." + loggerName) : "");
            current = current.getParent();
        }
        logName = ContainerBase.class.getName() + "." + loggerName;
        return logName;
        
    }

    
    // -------------------- JMX and Registration  --------------------
    protected String type;
    protected String domain;
    protected String suffix;
    protected ObjectName oname;
    protected ObjectName controller;
    protected MBeanServer mserver;

    public ObjectName getJmxName() {
        return oname;
    }
    
    public String getObjectName() {
        if (oname != null) {
            return oname.toString();
        } else return null;
    }

    public String getDomain() {
        if( domain==null ) {
            Container parent=this;
            while( parent != null &&
                    !( parent instanceof StandardEngine) ) {
                parent=parent.getParent();
            }
            if( parent instanceof StandardEngine ) {
                domain=((StandardEngine)parent).getDomain();
            } 
        }
        return domain;
    }

    public void setDomain(String domain) {
        this.domain=domain;
    }
    
    public String getType() {
        return type;
    }

    protected String getJSR77Suffix() {
        return suffix;
    }

    public ObjectName preRegister(MBeanServer server,
                                  ObjectName name) throws Exception {
        oname=name;
        mserver=server;
        if (name == null ){
            return null;
        }

        domain=name.getDomain();

        type=name.getKeyProperty("type");
        if( type==null ) {
            type=name.getKeyProperty("j2eeType");
        }

        String j2eeApp=name.getKeyProperty("J2EEApplication");
        String j2eeServer=name.getKeyProperty("J2EEServer");
        if( j2eeApp==null ) {
            j2eeApp="none";
        }
        if( j2eeServer==null ) {
            j2eeServer="none";
        }
        suffix=",J2EEApplication=" + j2eeApp + ",J2EEServer=" + j2eeServer;
        return name;
    }

    public void postRegister(Boolean registrationDone) {
    }

    public void preDeregister() throws Exception {
    }

    public void postDeregister() {
    }

    public ObjectName[] getChildren() {
        ObjectName result[]=new ObjectName[children.size()];
        Iterator<Container> it=children.values().iterator();
        int i=0;
        while( it.hasNext() ) {
            Object next=it.next();
            if( next instanceof ContainerBase ) {
                result[i++]=((ContainerBase)next).getJmxName();
            }
        }
        return result;
    }

    public ObjectName createObjectName(String domain, ObjectName parent)
        throws Exception
    {
        if( log.isDebugEnabled())
            log.debug("Create ObjectName " + domain + " " + parent );
        return null;
    }

    public String getContainerSuffix() {
        Container container=this;
        Container context=null;
        Container host=null;
        Container servlet=null;
        
        StringBuilder suffix=new StringBuilder();
        
        if( container instanceof StandardHost ) {
            host=container;
        } else if( container instanceof StandardContext ) {
            host=container.getParent();
            context=container;
        } else if( container instanceof StandardWrapper ) {
            context=container.getParent();
            host=context.getParent();
            servlet=container;
        }
        if( context!=null ) {
            String path=((StandardContext)context).getPath();
            suffix.append(",path=").append((path.equals("")) ? "/" : path);
        } 
        if( host!=null ) suffix.append(",host=").append( host.getName() );
        if( servlet != null ) {
            String name=container.getName();
            suffix.append(",servlet=");
            suffix.append((name=="") ? "/" : name);
        }
        return suffix.toString();
    }


    /**
     * Start the background thread that will periodically check for
     * session timeouts.
     */
    protected void threadStart() {

        if (thread != null)
            return;
        if (backgroundProcessorDelay <= 0)
            return;

        threadDone = false;
        String threadName = "ContainerBackgroundProcessor[" + toString() + "]";
        thread = new Thread(new ContainerBackgroundProcessor(), threadName);
        thread.setDaemon(true);
        thread.start();

    }


    /**
     * Stop the background thread that is periodically checking for
     * session timeouts.
     */
    protected void threadStop() {

        if (thread == null)
            return;

        threadDone = true;
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            // Ignore
        }

        thread = null;

    }


    // -------------------------------------- ContainerExecuteDelay Inner Class


    /**
     * Private thread class to invoke the backgroundProcess method 
     * of this container and its children after a fixed delay.
     */
    protected class ContainerBackgroundProcessor implements Runnable {

        public void run() {
            while (!threadDone) {
                try {
                    Thread.sleep(backgroundProcessorDelay * 1000L);
                } catch (InterruptedException e) {
                    // Ignore
                }
                if (!threadDone) {
                    Container parent = (Container) getMappingObject();
                    ClassLoader cl = 
                        Thread.currentThread().getContextClassLoader();
                    if (parent.getLoader() != null) {
                        cl = parent.getLoader().getClassLoader();
                    }
                    processChildren(parent, cl);
                }
            }
        }

        protected void processChildren(Container container, ClassLoader cl) {
            try {
                if (container.getLoader() != null) {
                    Thread.currentThread().setContextClassLoader
                        (container.getLoader().getClassLoader());
                }
                container.backgroundProcess();
            } catch (Throwable t) {
                log.error("Exception invoking periodic operation: ", t);
            } finally {
                Thread.currentThread().setContextClassLoader(cl);
            }
            Container[] children = container.findChildren();
            for (int i = 0; i < children.length; i++) {
                if (children[i].getBackgroundProcessorDelay() <= 0) {
                    processChildren(children[i], cl);
                }
            }
        }

    }

}
