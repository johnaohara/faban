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
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.faban.harness.services;

import com.sun.faban.common.NameValuePair;
import com.sun.faban.common.ParamReader;
import com.sun.faban.harness.ConfigurationException;
import com.sun.faban.harness.ParamRepository;
import com.sun.faban.harness.common.Config;
import com.sun.faban.harness.common.Run;
import com.sun.faban.harness.engine.CmdService;
import com.sun.faban.harness.engine.DeployImageClassLoader;
import com.sun.faban.harness.tools.MasterToolContext;
import com.sun.faban.harness.tools.ToolDescription;
import com.sun.faban.harness.util.XMLReader;
import java.rmi.RemoteException;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class manages all the services.
 *
 * @author Sheetal Patil
 */
public class ServiceManager {

    private static Logger logger =
            Logger.getLogger(ServiceContext.class.getName());

    private static ServiceManager instance = null;

    private List<ServiceWrapper> loadedServicesList = 
            new ArrayList<ServiceWrapper>();
    private List<ServiceContext> ctxList = new ArrayList<ServiceContext>();

    private LinkedHashMap<String, ServiceDescription> serviceMap =
            new LinkedHashMap<String, ServiceDescription>();

    private LinkedHashMap<String, ToolDescription> toolMap =
            new LinkedHashMap<String, ToolDescription>();

    private LinkedHashMap<String, List<String>> toolSetsMap =
            new LinkedHashMap<String, List<String>>();

    ArrayList<MasterToolContext> toolList = new ArrayList<MasterToolContext>();

    HashSet<String> activeDeployments = new HashSet<String>();

    ArrayList<NameValuePair<String>> hostDeploymentList =
                                        new ArrayList<NameValuePair<String>>();


    private Run run;

    /**
     * Obtains the set of active services and tools deployments used in the
     * current run.
     * @return The set of active deployements or null if there is no current run
     */
    public static Set<String> getActiveDeployments() {
        Set<String> deployments = null;
        if (instance != null) {
            deployments = instance.activeDeployments;
        }
        return deployments;
    }

    /**
     * Constructor.
     * @param par
     * @param run
     * @throws java.lang.Exception
     */
    public ServiceManager(ParamRepository par, Run run)
            throws Exception{
        String benchmark = run.getBenchmarkName();
        this.run = run;
        // Get the service descriptions and tool descriptions
        parseAvailableServices(benchmark);

        // Obtain the active service list.
        parseRequestedServices(par);
        
        initiateDownload();

        for (ServiceContext ctx : ctxList) {            
            DeployImageClassLoader loader = DeployImageClassLoader.getInstance(
                                ctx.desc.locationType, ctx.desc.location,
                                getClass().getClassLoader());
            ServiceWrapper wrapper = new ServiceWrapper(
                                loader.loadClass(ctx.desc.serviceClass), ctx);
            loadedServicesList.add(wrapper);

        }
        this.loadedServicesList = Collections.unmodifiableList(
                                                            loadedServicesList);
        this.ctxList = Collections.unmodifiableList(ctxList);

        instance = this;
    }
  
    private void parseAvailableServices(String benchmark) {
        parseServicesAndTools("benchmarks", benchmark);
        parseToolSets("benchmarks", benchmark);
        File serviceDir = new File(Config.SERVICE_DIR);
        File[] serviceBundles;
        if (serviceDir.isDirectory()) {
            serviceBundles = serviceDir.listFiles();
            for (File serviceBundle : serviceBundles) {
                if (!serviceBundle.isDirectory())
                    continue;
                parseServicesAndTools("services", serviceBundle.getName());
                parseToolSets("services", serviceBundle.getName());
            }
            bindServices();
        }
    }

    /**
     * Parses a service/tool bundle.
     * @param type The location type, services or benchmark
     * @param dir The deploy jar name, without .jar
     */
    public void parseServicesAndTools(String type, String dir) {

        String metaInf = Config.FABAN_HOME + File.separator + type +
                File.separator + dir + File.separator + "META-INF";
        File metaInfDir = new File(metaInf);
        if (!metaInfDir.isDirectory()) {
            return;
        }
        File serviceXml = new File(metaInf + File.separator +
                    "services-tools.xml");
        try {
            if (serviceXml.exists()) {
                XMLReader reader = new XMLReader(metaInf + File.separator +
                        "services-tools.xml");
                Element root = null;
                if (reader != null) {
                    root = reader.getRootNode();

                    // First, parse the services.
                    NodeList serviceNodes = reader.getNodes("service", root);
                    for (int i = 0; i < serviceNodes.getLength(); i++) {

                        Node serviceNode = serviceNodes.item(i);
                        if (serviceNode.getNodeType() != Node.ELEMENT_NODE) {
                            continue;
                        }
                        Element se = (Element) serviceNode;
                        String id = null;
                        NamedNodeMap attrList = serviceNode.getAttributes();
                        for (int j = 0; j < attrList.getLength(); j++) {
                            if (attrList.item(j).getNodeType() ==
                                    Node.ATTRIBUTE_NODE) {
                                if (attrList.item(j).getNodeName().equals("id")) {
                                    id = attrList.item(j).getNodeValue();
                                }
                            }
                        }
                        NodeList classNodes = serviceNode.getChildNodes();
                        String loadableClass = null;
                        for (int k = 0; k < classNodes.getLength(); k++) {
                            if (classNodes.item(k).getNodeType() ==
                                    Node.ELEMENT_NODE) {
                                loadableClass = reader.getValue("class", se);

                            }
                        }
                        if (id != null && loadableClass != null) {
                            if (serviceMap.containsKey(id)) {
                                logger.log(Level.WARNING,
                                        "Ignoring duplicate service " + id +
                                        " in " + type + File.separator + dir);
                            } else {
                                ServiceDescription desc =
                                        new ServiceDescription(id,
                                                loadableClass, type, dir);
                                serviceMap.put(id, desc);
                            }
                        }
                    }

                    // Then parse the tools.
                    NodeList toolNodes = reader.getNodes("tool", root);
                    for (int i = 0; i < toolNodes.getLength(); i++) {
                        String id = null;
                        String serviceName = null;
                        String toolClass = null;
                        Node toolNode = toolNodes.item(i);
                        if (toolNode.getNodeType() != Node.ELEMENT_NODE) {
                            continue;
                        }
                        Element te = (Element) toolNode;
                        NamedNodeMap attrList = toolNode.getAttributes();
                        for (int j = 0; j < attrList.getLength(); j++) {
                            if (attrList.item(j).getNodeType() ==
                                    Node.ATTRIBUTE_NODE) {
                                if (attrList.item(j).getNodeName().equals("id")) {
                                    id = attrList.item(j).getNodeValue();
                                }
                                if (attrList.item(j).getNodeName().
                                        equals("service")) {
                                    serviceName = attrList.item(j).getNodeValue();
                                }
                            }
                        }
                        NodeList classNodes = toolNode.getChildNodes();
                        for (int k = 0; k < classNodes.getLength(); k++) {
                            if (classNodes.item(k).getNodeType() ==
                                    Node.ELEMENT_NODE) {
                                toolClass = reader.getValue("class", te);
                            }
                        }

                        if (id != null) {
                            String key = id;
                            if (serviceName != null) {
                                key += '/' + serviceName;
                                if (toolMap.containsKey(key)) {
                                    logger.log(Level.WARNING,
                                            "Ignoring duplicate tool" + id);
                                } else {
                                    toolMap.put(key, new ToolDescription(id,
                                            serviceName, toolClass, type, dir));
                                }
                            }
                        }
                    }
                }
            }
        } catch  (Exception e) {
            logger.log(Level.WARNING, "Error reading benchmark " +
                    "descriptor for " + dir, e);
        }
    }

    private void bindServices() {
        Iterator<Map.Entry<String, ToolDescription>> iter =
                toolMap.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, ToolDescription> entry = iter.next();
            ToolDescription toolDesc = entry.getValue();
            if (!toolDesc.bind(serviceMap))
                logger.warning("Tool " + toolDesc.getId() + " at " +
                        toolDesc.getLocationType() + File.separator +
                        toolDesc.getLocation() +
                        " references non-existent service " +
                        toolDesc.getServiceName());
        }
    }


    private void parseRequestedServices(ParamRepository par)
            throws IOException, ConfigurationException {
        HashSet<NameValuePair<String>> hostDeploymentSet =
                new HashSet<NameValuePair<String>>();

        NodeList topLevelElements = par.getTopLevelElements();
        int topLevelSize = topLevelElements.getLength();
        Properties properties = null;
        for (int i = 0; i < topLevelSize; i++) {
            Node node = topLevelElements.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element ti = (Element) node;
            String ns = ti.getNamespaceURI();
            String topElement = ti.getNodeName();
            if (ParamReader.FABANURI.equals(ns) && "fa:runConfig".
                    equals(topElement))
                continue;
            if("http://faban.sunsource.net/ns/fabanharness".equals(ns) &&
                    "jvmConfig".equals(topElement))
                continue;

            // Get the hosts
            String[] hosts = par.getEnabledHosts(ti);
            if (hosts == null || hosts.length == 0)
                continue;

            // Get the services
            NodeList serviceNodes = par.getNodes("fh:service", ti);
            int serviceCount = serviceNodes.getLength();
            for (int j = 0; j < serviceCount; j++) {
                Element serviceElement = (Element) serviceNodes.item(j);
                String serviceName = par.getParameter("fh:name",
                                                        serviceElement);
                boolean restart = par.getBooleanValue("fh:restart",
                                                      serviceElement);
                Node configNode = par.getNode("fh:config", serviceElement);
                if(configNode != null) {
                    properties = new Properties();
                    NodeList propsList = configNode.getChildNodes();
                    for (int k = 0; k < propsList.getLength(); k++) {
                        if (propsList.item(k).getNodeType() ==
                                Node.ELEMENT_NODE) {
                            Element propElement = (Element) propsList.item(k);
                            String key = propsList.item(k).getNodeName();
                            NodeList props = propElement.getChildNodes();
                            int propValueLength = props.getLength();
                            for (int l = 0; l < propValueLength; l++) {
                                Node valueNode = (Node) props.item(l);
                                if (valueNode.getNodeType() == Node.TEXT_NODE) {
                                    String value =
                                            valueNode.getNodeValue().trim();
                                    properties.setProperty(key, value);
                                    break;
                                }
                            }
                        }
                    }
                }
                ServiceDescription sd = serviceMap.get(serviceName);

                if (sd == null) {
                    logger.warning("Cannot find service: " + serviceName +
                                   ". Service may not be deployed.");
                    continue;
                }

                // Benchmarks get loaded automatically and have their checks.
                // We need to mark a service only in the services directory.
                if ("services".equals(sd.locationType)) {
                    File runIdFile = new File(Config.SERVICE_DIR +
                                sd.location + File.separator +
                                "META-INF" + File.separator + "RunID");
                    FileWriter wFile = new FileWriter(runIdFile);
                    wFile.write(run.getRunId());
                    wFile.close();
                }

                ServiceContext ctx =
                        new ServiceContext(sd, par, ti, properties, restart);
                ctxList.add(ctx);
                if ("services".equals(sd.locationType)) {
                    String fullLocation = sd.locationType + '/' + sd.location;
                    activeDeployments.add(fullLocation);

                    CmdService cmds = CmdService.getHandle();
                    String[] ctxHosts = ctx.getHosts();
                    for (String ctxHost : ctxHosts) {
                        if (hostDeploymentSet.add(new NameValuePair<String>(
                                cmds.getHostName(ctxHost), sd.location))) {
                            hostDeploymentList.add(new NameValuePair<String>(
                                    ctxHost, sd.location));
                        }
                    }
                }
                String toolCmds = par.getParameter("fh:tools", serviceElement);
                Set<String> tools = new LinkedHashSet<String>();
                if (toolCmds.toUpperCase().equals("NONE")) {
                }else if(toolCmds.length() != 0){
                    StringTokenizer st = new StringTokenizer(toolCmds, ";");
                    while (st.hasMoreTokens()) {
                        tools.add(st.nextToken().trim());
                    }
                }else if ("".equals(toolCmds) && toolCmds.length() == 0){
                    String key = "default" + '/' + serviceName;
                    Set<String> toolset_tools = new LinkedHashSet<String>();
                    if(toolSetsMap.containsKey(key)){
                            toolset_tools.addAll(toolSetsMap.get(key));
                            for (String t1 : toolset_tools){
                                StringTokenizer tt1 = new StringTokenizer(t1);
                                String toolId1 = tt1.nextToken();
                                String toolKey1 = toolId1 + '/' + serviceName;
                                ToolDescription toolDesc = toolMap.get(toolKey1);
                                MasterToolContext toolCtx = null;
                                if (toolDesc != null) {
                                    toolCtx = new MasterToolContext(t1, ctx,
                                            toolDesc);
                                } else {
                                    toolDesc = toolMap.get(toolId1);
                                    if (toolDesc != null) {
                                        toolCtx = new MasterToolContext(
                                                t1, ctx, toolDesc);
                                        activeDeployments.add(
                                                toolDesc.getLocationType() +
                                                File.separator +
                                                toolDesc.getLocation());
                                    } else {
                                        //logger.info("No Tool Description for tool: " + t1);
                                        logger.fine("No Tool Description for tool: "
                                                + t1 +" ,so it's a command line tool");
                                        toolCtx = new MasterToolContext(
                                                t1, ctx, null);
                                    }
                                }
                                if (toolCtx != null) {
                                    toolList.add(toolCtx);
                                }
                            }
                        }

                }
                for (String tool : tools) {
                    StringTokenizer tt = new StringTokenizer(tool);
                    String toolId = tt.nextToken();
                    String toolKey = toolId + '/' + serviceName;
                    Set<String> toolset_tools = new LinkedHashSet<String>();
                    if (toolSetsMap.containsKey(toolKey)) {
                        toolset_tools.addAll(toolSetsMap.get(toolKey));
                        for (String t1 : toolset_tools) {
                            StringTokenizer tt1 = new StringTokenizer(t1);
                            String toolId1 = tt1.nextToken();
                            String toolKey1 = toolId1 + '/' + serviceName;
                            ToolDescription toolDesc = toolMap.get(toolKey1);
                            MasterToolContext toolCtx = null;
                            if (toolDesc != null) {
                                toolCtx = new MasterToolContext(t1, ctx, toolDesc);
                            } else {
                                toolDesc = toolMap.get(toolId1);
                                if (toolDesc != null) {
                                    toolCtx = new MasterToolContext(
                                            t1, ctx, toolDesc);
                                } else {
                                    //logger.info("No Tool Description for tool: " + t1);
                                    logger.fine("No Tool Description for tool: " + t1 + " ,so it's a command line tool");
                                    toolCtx = new MasterToolContext(
                                            t1, ctx, null);
                                }
                            }
                            if (toolCtx != null) {
                                toolList.add(toolCtx);
                            }
                        }
                    } else {
                        ToolDescription toolDesc = toolMap.get(toolKey);
                        MasterToolContext toolCtx = null;
                        if (toolDesc != null) {
                            toolCtx = new MasterToolContext(tool, ctx, toolDesc);
                        } else {
                            toolDesc = toolMap.get(toolId);
                            if (toolDesc != null) {
                                toolCtx = new MasterToolContext(
                                        tool, ctx, toolDesc);
                            } else {
                                //logger.info("No Tool Description for tool: " + tool);
                                logger.fine("No Tool Description for tool: " + tool + " ,so it's a command line tool");
                                toolCtx = new MasterToolContext(
                                        tool, ctx, null);
                            }
                        }
                        if (toolCtx != null) {
                            toolList.add(toolCtx);
                        }
                    }
                }
                for (MasterToolContext toolCtx : toolList) {
                    String locationType =
                            toolCtx.getToolDescription().getLocationType();
                    if ("services".equals(locationType)) {
                        String location =
                                toolCtx.getToolDescription().getLocation();
                        String fullLocation = locationType + '/' + location;
                        activeDeployments.add(fullLocation);

                        CmdService cmds = CmdService.getHandle();
                        String[] ctxHosts =
                                toolCtx.getToolServiceContext().getHosts();
                        for (String ctxHost : ctxHosts) {
                            if (hostDeploymentSet.add(new NameValuePair<String>(
                                    cmds.getHostName(ctxHost), location))) {
                                hostDeploymentList.add(new NameValuePair<String>
                                        (ctxHost, location));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Parses and creates the appropriate structures for the tool sets.
     * @param type The location type, services or benchmark
     * @param dir The deploy jar name, without .jar
     */
    public void parseToolSets(String type, String dir) {
        String metaInf = Config.FABAN_HOME + File.separator + type +
                File.separator + dir + File.separator + "META-INF";
        File metaInfDir = new File(metaInf);
        if (!metaInfDir.isDirectory()) {
            return;
        }
        File toolsetsXml = new File(metaInf + File.separator +
                    "toolsets.xml");
        try {
            if(toolsetsXml.exists()){
                XMLReader reader = new XMLReader(metaInf + File.separator +
                        "toolsets.xml");
                Element root = null;
                if (reader != null) {
                    root = reader.getRootNode();

                    // First, parse the services.
                    NodeList toolsetsNodes = reader.getNodes("toolset", root);
                    for (int i = 0; i < toolsetsNodes.getLength(); i++) {

                        Node toolsetsNode = toolsetsNodes.item(i);
                        if (toolsetsNode.getNodeType() != Node.ELEMENT_NODE) {
                            continue;
                        }
                        Element tse = (Element) toolsetsNode;
                        ArrayList<String> toolsCmds = new ArrayList<String>();
                        String service = reader.getValue("service", tse);
                        String name = reader.getValue("name", tse);
                        String base = reader.getValue("base", tse);
                        List<String> toolIncludes = reader.getValues("includes", tse);
                        List<String> toolExcludes = reader.getValues("excludes", tse);
                        if(!"".equals(base)){
                            toolsCmds.addAll(toolSetsMap.get(base+"/"+service));
                        }
                        if(toolIncludes != null){
                            for (String tool : toolIncludes){
                                StringTokenizer st = new StringTokenizer(tool, ";");
                                while(st.hasMoreTokens())
                                    toolsCmds.add(st.nextToken().trim());
                            }
                        }
                        if(toolExcludes != null){
                            ArrayList<String> td = new ArrayList<String>();
                            for (String tool : toolExcludes){
                                StringTokenizer st = new StringTokenizer(tool, ";");
                                while(st.hasMoreTokens())
                                    td.add(st.nextToken().trim());
                            }
                            toolsCmds.removeAll(td);
                        }

                        if (!"".equals(service) && !"".equals(name) &&
                                (toolIncludes != null || base != null)) {
                            String key = name+"/"+service;
                            if (toolSetsMap.containsKey(key)) {
                                logger.log(Level.WARNING,
                                        "Ignoring duplicate toolset = " + key);
                            } else {
                                toolSetsMap.put(key, toolsCmds);
                            }
                        }
                    }
                }
            }
        } catch  (Exception e) {
            logger.log(Level.WARNING, "Error reading toolsets.xml " +
                    "for " + dir, e);
        }        
    }

    private void initiateDownload() throws RemoteException {
        HashMap<String, List<String>> downloadMap = new HashMap<String, List<String>>();
        for (NameValuePair<String> hostPath : hostDeploymentList) {
            List<String> pathList = downloadMap.get(hostPath.name);
            if (pathList == null) {
                pathList = new ArrayList<String>();
                downloadMap.put(hostPath.name, pathList);
            }
            pathList.add(hostPath.value);
        }

        Set<Map.Entry<String, List<String>>> entrySet = downloadMap.entrySet();
        CmdService cmds = CmdService.getHandle();
        for (Map.Entry<String, List<String>> entry : entrySet) {
            cmds.downloadServices(entry.getKey(), entry.getValue());
        }

        // Also update the paths on the local command agent.
        ArrayList<String> activeServiceList = new ArrayList<String>();
        for (String deployment : activeDeployments) {
            if (deployment.startsWith("service/"))
                activeServiceList.add(deployment.substring(8));
        }
        cmds.updatePaths(activeServiceList);
    }

    /**
     * Returns a list of MasterToolContext.
     * @return List
     */
    public List<MasterToolContext> getTools() {
        return toolList;
    }

    /**
     * Configures the service.
     */
    public void configure() {
        for(ServiceWrapper sw : loadedServicesList){
            try {
                sw.configure();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to configure service " +
                        sw.ctx.desc.id, e);
            }
        }
    }

    /**
     * Obtains the configuration of a service.
     */
    public void getConfig() {
        for(ServiceWrapper sw : loadedServicesList){
            try {
                sw.getConfig();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to obtain service " +
                        "configuration for service " + sw.ctx.desc.id, e);
            }
        }
    }

    /**
     * Obtains the logs of a service.
     */
    public void getLogs() {
        for(ServiceWrapper sw : loadedServicesList){
            try {
                sw.getLogs();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to obtain service logs " +
                        "for service " + sw.ctx.desc.id, e);
             }
        }
    }

    /**
     * Starts a service. If the service is marked for restart,
     * this will shutdown the service and restart it.
     */
    public void startup() {
        // Use two separate loops to leave some time
        // between shutdown and startup.
        for(ServiceWrapper sw : loadedServicesList){
            if (sw.ctx.restart) {
                try {
                    sw.shutdown();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to shutdown service " +
                            sw.ctx.desc.id, e);
                }             
            }
        }

        for(ServiceWrapper sw : loadedServicesList){
            if (sw.ctx.restart) {
                try {
                    sw.startup();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to startup service " +
                            sw.ctx.desc.id, e);
                }
            }
        }
    }

    /**
     * Stops a service.
     */
    public void shutdown() {
        for (ServiceContext ctx : ctxList) {
            if ("services".equals(ctx.desc.locationType)) {
                File runIdFile = new File(Config.SERVICE_DIR +
                        ctx.desc.location + File.separator +
                        "META-INF" + File.separator + "RunID");
                if (runIdFile.exists())
                    runIdFile.delete();
            }
        }
        for(ServiceWrapper sw : loadedServicesList){
            if (sw.ctx.restart)
                try {
                    sw.shutdown();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to shutdown service " +
                            sw.ctx.desc.id, e);
                }
        }
        instance = null;
    }
}
