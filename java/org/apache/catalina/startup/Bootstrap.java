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
package org.apache.catalina.startup;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * Daemon object used by main.
     */
    private static final Object daemonLock = new Object();

    private static volatile Bootstrap daemon = null;

    // -------------------------------------------------------------- Variables

    /**
     * Daemon reference.
     */
    private Object catalinaDaemon = null;

    ClassLoader commonLoader = null;

    ClassLoader catalinaLoader = null;

    ClassLoader sharedLoader = null;

    // -------------------------------------------------------- Private Methods

    private void initClassLoaders() {
        try {
            // Tomcat 共享的类加载器
            commonLoader = createClassLoader("common", null);
            if (commonLoader == null) {
                // no config file, default to this loader - we might be in a 'single' env.
                commonLoader = this.getClass().getClassLoader();
            }
            catalinaLoader = createClassLoader("server", commonLoader);
            sharedLoader = createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }

    private ClassLoader createClassLoader(String name, ClassLoader parent)
            throws Exception {

        // 获取 ${name} 类加载器的 classpath
        String value = CatalinaProperties.getProperty(name + ".loader");

        // 如果没有配置类路径，就不创建新的类加载器，使用 parent
        if ((value == null) || (value.equals("")))
            return parent;

        // 解析占位符
        value = replace(value);

        List<Repository> repositories = new ArrayList<Repository>();

        // 使用 StringTokenizer 对字符串进行分割，分隔符是 ","
        StringTokenizer tokenizer = new StringTokenizer(value, ",");
        while (tokenizer.hasMoreElements()) {
            // 获取下一个 classpath
            String repository = tokenizer.nextToken().trim();
            if (repository.length() == 0) { // 字符串出现 ,, 连续的 , ?
                continue;
            }

            // Check for a JAR URL repository
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository); // 只是确保 URL 是否正确，不正确则忽略

                // 添加 repository
                repositories.add(new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            // Local repository
            // 如果以 *.jar 结尾，这是一个 glob pattern
            if (repository.endsWith("*.jar")) {
                repository = repository.substring(0, repository.length() - "*.jar".length());
                repositories.add(new Repository(repository, RepositoryType.GLOB));
            }
            // 如果以 .jar 结尾，这是一个 jar 文件
            else if (repository.endsWith(".jar")) {
                repositories.add(new Repository(repository, RepositoryType.JAR));
            }
            // 否则就是一个目录 ? (有点草率)
            else {
                repositories.add(new Repository(repository, RepositoryType.DIR));
            }
        }

        // repositories 2 个元素，URL 还有另一个类型的仓库
        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }

    /**
     * System property replacement in the given string.
     *
     * @param str The original string
     * @return the modified string
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                // 拼接上一个 } 到下一个 ${ 之间的字符串
                builder.append(str, pos_end + 1, pos_start);

                // 寻找 }，位置起码是从 pos_start + 2 开始，跳过 ${ 两个字符
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }

                // 将 ${} 解析成属性并拼接
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                }
                // 处理 ${catalina.home} 属性
                else if (Globals.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                }
                // 处理 ${catalina.base} 属性
                else if (Globals.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }

    /**
     * Initialize daemon.
     *
     * @throws Exception Fatal initialization error
     */
    public void init() throws Exception {

        // Set Catalina path

        // 设置 catalina.home 环境变量
        setCatalinaHome();

        // 设置 catalina.base 环境变量
        setCatalinaBase();

        // 初始化类加载器
        initClassLoaders();

        // 设置使用 catalinaLoader 类加载器
        Thread.currentThread().setContextClassLoader(catalinaLoader);

        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method
        if (log.isDebugEnabled())
            log.debug("Loading startup class");

        // 加载 Catalina 类，并实例化
        Class<?> startupClass =
                catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
        Object startupInstance = startupClass.newInstance();

        // Set the shared extensions class loader
        if (log.isDebugEnabled())
            log.debug("Setting startup class properties");

        // 调用 Catalina 的 setParentClassLoader 方法
        // 只是为了设置 父类加载器，且设置为 shared (表示共享)
        String methodName = "setParentClassLoader";
        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        paramValues[0] = sharedLoader;
        Method method =
                startupInstance.getClass().getMethod(methodName, paramTypes);
        method.invoke(startupInstance, paramValues);

        catalinaDaemon = startupInstance;
    }

    /**
     * Load daemon.
     */
    private void load(String[] arguments) throws Exception {

        // Call the load() method
        // 方法的名字就叫 load
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[]; // 用于反射寻找 Method
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
                catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled()) {
            log.debug("Calling startup class " + method);
        }
        method.invoke(catalinaDaemon, param);
    }

    /**
     * getServer() for configtest
     */
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method = catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);
    }

    // ----------------------------------------------------------- Main Program

    /**
     * Load the Catalina daemon.
     *
     * @param arguments Initialization arguments
     * @throws Exception Fatal initialization error
     */
    public void init(String[] arguments) throws Exception {

        // 初始化 [比如系统属性，Catalina 实例对象创建]
        init();

        // 调用 catalina 的 load 方法
        load(arguments);
    }

    /**
     * Start the Catalina daemon.
     *
     * @throws Exception Fatal start error
     */
    public void start() throws Exception {
        if (catalinaDaemon == null) {
            init();
        }

        Method method = catalinaDaemon.getClass().getMethod("start", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }

    /**
     * Stop the Catalina Daemon.
     *
     * @throws Exception Fatal stop error
     */
    public void stop() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stop", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }

    /**
     * Stop the standalone server.
     *
     * @throws Exception Fatal stop error
     */
    public void stopServer() throws Exception {

        Method method =
                catalinaDaemon.getClass().getMethod("stopServer", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }

    /**
     * Stop the standalone server.
     *
     * @param arguments Command line arguments
     * @throws Exception Fatal stop error
     */
    public void stopServer(String[] arguments) throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
                catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);
    }

    /**
     * Set flag.
     *
     * @param await <code>true</code> if the daemon should block
     * @throws Exception Reflection error
     */
    public void setAwait(boolean await)
            throws Exception {

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method =
                catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);
    }

    public boolean getAwait() throws Exception {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
                catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b = (Boolean) method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }

    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }

    /**
     * Main method and entry point when starting Tomcat via the provided
     * scripts.
     *
     * @param args Command line arguments to be processed
     */
    public static void main(String args[]) {

        synchronized (daemonLock) {
            if (daemon == null) {
                // Don't set daemon until init() has completed
                Bootstrap bootstrap = new Bootstrap();
                try {
                    bootstrap.init();
                } catch (Throwable t) {
                    handleThrowable(t);
                    t.printStackTrace();
                    return;
                }
                daemon = bootstrap;
            } else {
                // When running as a service the call to stop will be on a new
                // thread so make sure the correct class loader is used to
                // prevent a range of class not found exceptions.
                Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
            }
        }

        try {
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
                daemon.setAwait(true);
                daemon.load(args);
                daemon.start();
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    public void setCatalinaHome(String s) {
        System.setProperty(Globals.CATALINA_HOME_PROP, s);
    }

    public void setCatalinaBase(String s) {
        System.setProperty(Globals.CATALINA_BASE_PROP, s);
    }

    /**
     * Set the <code>catalina.base</code> System property to the current
     * working directory if it has not been set.
     */
    private void setCatalinaBase() {

        if (System.getProperty(Globals.CATALINA_BASE_PROP) != null)
            return;
        if (System.getProperty(Globals.CATALINA_HOME_PROP) != null)
            System.setProperty(Globals.CATALINA_BASE_PROP,
                    System.getProperty(Globals.CATALINA_HOME_PROP));
        else
            System.setProperty(Globals.CATALINA_BASE_PROP,
                    System.getProperty("user.dir"));

    }

    /**
     * Set the <code>catalina.home</code> System property to the current
     * working directory if it has not been set.
     * <p>
     * 如果没有设置catalina.home属性，则将其设置为当前工作目录。
     */
    private void setCatalinaHome() {

        // 如果属性设置，则不用处理
        if (System.getProperty(Globals.CATALINA_HOME_PROP) != null)
            return;

        // 否则获取 ${user.dir}/bootstrap.jar
        File bootstrapJar =
                new File(System.getProperty("user.dir"), "bootstrap.jar");
        if (bootstrapJar.exists()) {
            try {
                // 获取 user.dir 父目录 ?
                System.setProperty(Globals.CATALINA_HOME_PROP,
                        (new File(System.getProperty("user.dir"), "..")).getCanonicalPath());
            } catch (Exception e) {
                // Ignore
                System.setProperty(Globals.CATALINA_HOME_PROP,
                        System.getProperty("user.dir"));
            }
        } else {
            System.setProperty(Globals.CATALINA_HOME_PROP,
                    System.getProperty("user.dir"));
        }

    }

    /**
     * Get the value of the catalina.home environment variable.
     */
    public static String getCatalinaHome() {
        return System.getProperty(Globals.CATALINA_HOME_PROP,
                System.getProperty("user.dir"));
    }

    /**
     * Get the value of the catalina.base environment variable.
     */
    public static String getCatalinaBase() {
        return System.getProperty(Globals.CATALINA_BASE_PROP, getCatalinaHome());
    }

    // Copied from ExceptionUtils since that class is not visible during start
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }

}
