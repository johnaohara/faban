/* The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.sun.com/cddl/cddl.html or
 * install_dir/legal/LICENSE
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at install_dir/legal/LICENSE.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: DeployUtil.java,v 1.20 2009/06/29 21:29:08 akara Exp $
 *
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.faban.harness.util;

import com.sun.faban.common.Command;
import com.sun.faban.common.Utilities;
import com.sun.faban.harness.common.BenchmarkDescription;
import com.sun.faban.harness.common.Config;
import com.sun.faban.harness.engine.RunQ;
import com.sun.faban.harness.services.ServiceManager;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Benchmark deployment utility used to check/generate the deployment
 * descriptors, check proper deployment of all benchmarks or individual
 * benchmarks, and deploy them if appropriate.
 *
 * @author Akara Sucharitakul
 */
public class DeployUtil {

    public static final File BENCHMARKDIR;
    public static final File SERVICEDIR;

    private static Logger logger;
    static {
        logger = Logger.getLogger(DeployUtil.class.getName());
        BENCHMARKDIR = new File(Config.BENCHMARK_DIR);
        if (!BENCHMARKDIR.isDirectory()) {
            logger.severe(Config.BENCHMARK_DIR + " is not a directory");
            throw new RuntimeException(Config.BENCHMARK_DIR +
                    " is not a directory");
        }
        SERVICEDIR = new File(Config.SERVICE_DIR);
        if (!SERVICEDIR.isDirectory()) {
            logger.severe(Config.SERVICE_DIR + " is not a directory");
            throw new RuntimeException(Config.SERVICE_DIR +
                    " is not a directory");
        }
    }


    /**
     * Unjars the content a benchmark jar file into the benchmark directory.
     * @param benchName The name of the benchmark
     */
    public static void unjar(File jarFile, String benchName)
            throws IOException, Exception {
                
        logger.info("Redeploying " + benchName);

        String jarName = jarFile.getName();
        File dir = new File(Config.BENCHMARK_DIR, benchName);

        if (dir.exists())
            FileHelper.recursiveDelete(dir);

        dir.mkdir();

        FileHelper.unjar(jarFile.getAbsolutePath(), dir.getAbsolutePath());
        File runXml = new File(Config.BENCHMARK_DIR + benchName + File.separator + "META-INF" + File.separator + "run.xml");
        File servicesToolsXml = new File(Config.BENCHMARK_DIR + benchName + File.separator + "META-INF" + File.separator + "services-tools.xml");
        if (servicesToolsXml.exists() && !runXml.exists()) {
            logger.info("Redeploying Service " + benchName);

            File dir1 = new File(Config.SERVICE_DIR, benchName);
            if (dir1.exists()) {
                FileHelper.recursiveDelete(dir1);
            }
            dir1.mkdir();
            FileHelper.copyFile(Config.BENCHMARK_DIR + jarName, Config.SERVICE_DIR + jarName, false);
            FileHelper.recursiveCopy(dir, dir1);
            FileHelper.recursiveDelete(dir);
            FileHelper.recursiveDelete(new File(Config.BENCHMARK_DIR + jarName));
            /*String[] leftoverFiles = dir.list();

            if(leftoverFiles != null && leftoverFiles.length > 0){
                FileHelper.recursiveDelete(dir);
            }else{
                dir.delete();
            }*/
        }else{
            generateDD(benchName);
            generateXform(benchName);
        }
   
    }

    public static void generateDD(String dir) {
        String benchDir = Config.BENCHMARK_DIR + dir + File.separator;
        String metaInf = benchDir + "META-INF" + File.separator;
        String xmlPath = metaInf + "benchmark.xml";
        File benchmarkXml = new File(xmlPath);
        String configFile = null;
            try {
                if (benchmarkXml.exists()) {
                    DocumentBuilder parser = DocumentBuilderFactory.
                            newInstance().newDocumentBuilder();
                    Node root = parser.parse(benchmarkXml).getDocumentElement();
                    XPath xPath = XPathFactory.newInstance().newXPath();
                    configFile = xPath.evaluate("config-file-name", root);
                }

                if (configFile == null || configFile.length() == 0)
                    configFile = "run.xml";

                File libDir = new File(Config.LIB_DIR);
                String[] jarFiles = libDir.list();
                String classpath = null;
                for (int i = 0; i < jarFiles.length; i++)
                    if (jarFiles[i].endsWith(".jar")) {
                        if (classpath == null)
                            classpath = Config.LIB_DIR + jarFiles[i];
                        else
                            classpath += File.pathSeparator + Config.LIB_DIR +
                                     jarFiles[i];
                    }

                String benchLib = benchDir + "lib";
                File benchLibDir = new File(benchLib);
                jarFiles = benchLibDir.list();
                if (jarFiles == null)
                    throw new IOException("Benchmark lib directory " +
                                        benchLib + " empty or non-existent");

                for (int i = 0; i < jarFiles.length; i++)
                    if (jarFiles[i].endsWith(".jar"))
                        classpath += File.pathSeparator + benchLib +
                                     File.separator + jarFiles[i];

                ArrayList<String> ddCmd = new ArrayList<String>();
                ddCmd.add(Utilities.getJavaHome() + File.separator +
                               "bin" + File.separator + "java");
                ddCmd.add("-classpath");
                ddCmd.add(classpath);
                ddCmd.add("-Dbenchmark.config=" + configFile);
                ddCmd.add("-Dbenchmark.ddfile=faban.xml");
                ddCmd.add("-Djava.util.logging.config.file=" +
                               Config.CONFIG_DIR + "logging.properties");
                ddCmd.add("com.sun.faban.driver.util.DDGenerator");

                Command cmd = new Command(ddCmd);
                cmd.setWorkingDirectory(metaInf);
                cmd.execute();

            } catch (Exception e) {
                Logger logger = Logger.getLogger(DeployUtil.class.getName());
                logger.log(Level.WARNING, "Error generating FabanDriver " +
                        "descriptor for " + dir, e);
            }
    }

    public static void generateXform(String dir) throws Exception {
        String benchDir = Config.BENCHMARK_DIR + dir + File.separator;
        XPath xPath = XPathFactory.newInstance().newXPath();
        DocumentBuilder parser = DocumentBuilderFactory.
                            newInstance().newDocumentBuilder();
        String metaInf = benchDir + "META-INF" + File.separator;
        String xmlPath = metaInf + "benchmark.xml";
        File benchmarkXml = new File(xmlPath);
        String configForm = null;
        String configFile = null;
        
        if (benchmarkXml.exists()) {
            Node root = parser.parse(benchmarkXml).getDocumentElement();
            configForm = xPath.evaluate("config-form", root);
            configFile = xPath.evaluate("config-file-name", root);
        }

        if (configForm == null || configForm.length() == 0) {
            configForm = "config.xhtml";
        }

        if (configFile == null || configFile.length() == 0){
            configFile = "run.xml";
        }

        File configFormFile = new File(metaInf + configForm);
        if (!configFormFile.exists()) {
            File runFile = new File(metaInf + configFile);
            if (runFile.exists()) {
                File templateFile = new File(Config.FABAN_HOME + "resources/config-template.xhtml");
                XformsGenerator.generate(runFile, configFormFile, templateFile);
            }
        }

    }

    public static boolean canDeployBenchmark(String benchName) {
        RunQ runQ = RunQ.getHandle();

        if (benchName.equals(runQ.getCurrentBenchmark()))
            return false;

        String[][] pendingRuns = runQ.listRunQ();
        if (pendingRuns != null)
            for (int i = 0; i < pendingRuns.length; i++)
                if (benchName.equals(pendingRuns[i][RunQ.BENCHNAME]))
                    return false;
        return true;
    }

    public static boolean canDeployService(String serviceName) {
        Set<String> activeBundles = ServiceManager.getActiveDeployments();
        String serviceBundleName = "services" + File.separator + serviceName;
        if (activeBundles != null && activeBundles.contains(serviceBundleName))
            return false;
        return true;
    }

    public static void checkDeploy() {
        // Check for any jar files dropped in here, deploy if needed.
        File[] jarFiles = BENCHMARKDIR.listFiles();
        for (int i = 0; i < jarFiles.length; i++) {
            if (jarFiles[i].isDirectory())
                continue;
            String suffix = ".jar";
            String jarName = jarFiles[i].getName();
            if (!jarName.endsWith(suffix))
                continue;
            String benchName = jarName.substring(0,
                             jarName.length() - suffix.length());
            checkDeployBenchmark(jarFiles[i], benchName);
        }

        // TODO: Check for any jar files dropped in Service dir, deploy if needed.
        File[] jarFiles1 = SERVICEDIR.listFiles();
        for (int i = 0; i < jarFiles1.length; i++) {
            if (jarFiles1[i].isDirectory())
                continue;
            String suffix = ".jar";
            String jarName = jarFiles1[i].getName();
            if (!jarName.endsWith(suffix))
                continue;
            String benchName = jarName.substring(0,
                             jarName.length() - suffix.length());
            checkDeployService(jarFiles1[i], benchName);
        }

    }

    public static void checkDeployService(File jarFile, String serviceName) {
        File serviceDir = new File(SERVICEDIR, serviceName);
        if (serviceDir.isDirectory()) {
            if (jarFile.lastModified() > serviceDir.lastModified())
                deployService(jarFile, serviceName);
        } else {
            deployService(jarFile, serviceName);
        }
    }

    public static void checkDeployBenchmark(File jarFile, String benchName) {
        File benchDir = new File(BENCHMARKDIR, benchName);
        if (benchDir.isDirectory()) {
            if (jarFile.lastModified() > benchDir.lastModified())
                deployBenchmark(jarFile, benchName);
        } else {
            deployBenchmark(jarFile, benchName);
        }
    }

    public static void deployBenchmark(File jarFile, String benchName) {
        if (canDeployBenchmark(benchName))
            try {
                unjar(jarFile, benchName);
                generateDD(benchName);
                generateXform(benchName);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error deploying benchmark \"" +
                        benchName + "\"", e);
            }
    }

    public static void deployService(File jarFile, String serviceBundleName) {
        if (canDeployService(serviceBundleName))
            try {
                unjar(jarFile, serviceBundleName);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error deploying serviceBundle \"" +
                        serviceBundleName + "\"", e);
            }
    }

    public static void clearConfig(String benchName) {

        // 1. Figure out the config file name.
        BenchmarkDescription benchDesc = BenchmarkDescription.
                getDescription(benchName);
        if (benchDesc == null)
            return;
        
        String configFileName = benchDesc.configFileName + '.' + benchName;

        // 2. Go to the config/profiles directory and rename all config
        // files underneath.
        File[] profiles = new File(Config.PROFILES_DIR).listFiles();
        if (profiles != null && profiles.length > 0) {
            String date = null;
            for (File profile : profiles) {
                File configFile = new File(profile, configFileName);
                if (configFile.exists()) {
                    if (date == null) {
                        SimpleDateFormat format =
                                new SimpleDateFormat("yyMMddHHmmss");
                        date = "." + format.format(new Date());
                    }
                    File newName = new File(profile, configFileName + date);
                    configFile.renameTo(newName);
                } else if (profile.isFile() &&
                           configFileName.equals(profile.getName())) {
                    if (date == null) {
                        SimpleDateFormat format =
                                new SimpleDateFormat("yyMMddHHmmss");
                        date = "." + format.format(new Date());
                    }
                    File newName = new File(Config.PROFILES_DIR,
                                                configFileName + date);
                    profile.renameTo(newName);
                }
            }
        }
    }
}
