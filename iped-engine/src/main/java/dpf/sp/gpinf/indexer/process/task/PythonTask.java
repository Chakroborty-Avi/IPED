package dpf.sp.gpinf.indexer.process.task;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dpf.sp.gpinf.indexer.Messages;
import dpf.sp.gpinf.indexer.config.ConfigurationManager;
import dpf.sp.gpinf.indexer.config.LocalConfig;
import dpf.sp.gpinf.indexer.search.IPEDSearcher;
import dpf.sp.gpinf.indexer.search.IPEDSource;
import dpf.sp.gpinf.indexer.util.ImageUtil;
import iped3.ICaseData;
import iped3.IItem;
import jep.Jep;
import jep.JepException;
import jep.NDArray;
import jep.SharedInterpreter;

public class PythonTask extends AbstractTask {

    private static final String JEP_NOT_FOUND = Messages.getString("PythonTask.JepNotFound");
    private static final String DISABLED = Messages.getString("PythonTask.ModuleDisabled");
    private static final String SEE_MANUAL = Messages.getString("PythonTask.SeeManual");

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonTask.class);
    private static volatile JepException jepException = null;
    private static final Map<Long, Jep> jepPerThread = new HashMap<>();
    private static volatile File lastInstalledScript;
    private static volatile IPEDSource ipedCase;
    private static volatile int numInstances = 0;

    private ArrayList<String> globals = new ArrayList<>();
    private File scriptFile;
    private String moduleName, instanceName;
    private Properties confParams;
    private File confDir;
    private Boolean processQueueEnd;
    private boolean isEnabled = true;
    private boolean scriptLoaded = false;

    public PythonTask(File scriptFile) {
        this.scriptFile = scriptFile;
    }

    public void setCaseData(ICaseData caseData) {
        super.caseData = caseData;
    }

    private class ArrayConverter {
        public NDArray<?> getNDArray(byte[] array) {
            return new NDArray(array);
        }

        public NDArray<?> getNDArray(int[] array) {
            return new NDArray(array);
        }

        public NDArray<?> getNDArray(long[] array) {
            return new NDArray(array);
        }

        public NDArray<?> getNDArray(float[] array) {
            return new NDArray(array);
        }

        public NDArray<?> getNDArray(double[] array) {
            return new NDArray(array);
        }

        public NDArray<?> getNDArray(boolean[] array) {
            return new NDArray(array);
        }

        public NDArray<?> getNDArray(short[] array) {
            return new NDArray(array);
        }

        public NDArray<?> getNDArray(char[] array) {
            return new NDArray(array);
        }
    }

    private Jep getJep() throws JepException {
        synchronized (jepPerThread) {
            Jep jep = jepPerThread.get(Thread.currentThread().getId());
            if (jep == null) {
                jep = getNewJep();
                jepPerThread.put(Thread.currentThread().getId(), jep);
            }
            if (!scriptLoaded) {
                loadScript(jep);
                scriptLoaded = true;
            }
            return jep;
        }
    }

    private Jep getNewJep() throws JepException {

        Jep jep;
        try {
            jep = new SharedInterpreter();

        } catch (UnsatisfiedLinkError e) {
            if (jepException == null) {
                String msg = JEP_NOT_FOUND + SEE_MANUAL;
                jepException = new JepException(msg, e);
                LOGGER.error(msg);
                jepException.printStackTrace();
            }
            isEnabled = false;
            return null;
        }

        jep.eval("from jep import redirect_streams");
        jep.eval("redirect_streams.setup()");

        setGlobalVar(jep, "caseData", this.caseData); //$NON-NLS-1$
        setGlobalVar(jep, "moduleDir", this.output); //$NON-NLS-1$
        setGlobalVar(jep, "worker", this.worker); //$NON-NLS-1$
        setGlobalVar(jep, "stats", this.stats); //$NON-NLS-1$
        setGlobalVar(jep, "logger", LOGGER); //$NON-NLS-1$
        setGlobalVar(jep, "javaArray", new ArrayConverter()); //$NON-NLS-1$
        setGlobalVar(jep, "ImageUtil", new ImageUtil()); //$NON-NLS-1$

        LocalConfig localConfig = (LocalConfig) ConfigurationManager.getInstance().findObjects(LocalConfig.class)
                .iterator().next();
        setGlobalVar(jep, "numThreads", Integer.valueOf(localConfig.getNumThreads()));

        jep.eval("import sys");
        jep.eval("sys.path.append('" + scriptFile.getParentFile().getAbsolutePath().replace("\\", "\\\\") + "')");

        return jep;
    }

    private void setGlobalVar(Jep jep, String name, Object obj) throws JepException {
        jep.set(name, obj); // $NON-NLS-1$
        globals.add(name);
    }

    private void setModuleVar(Jep jep, String moduleName, String name, Object obj) throws JepException {
        setGlobalVar(jep, name, obj);
        jep.eval(moduleName + "." + name + " = " + name);
    }

    private void loadScript(Jep jep) throws JepException {

        if (jep == null) {
            return;
        }

        String className = scriptFile.getName().replace(".py","");
        moduleName = className;

        jep.eval("import " + moduleName);

        instanceName = className.toLowerCase() + "_thread_" + Thread.currentThread().getId();
        jep.eval(instanceName + " = " + moduleName + "." + className + "()");

        for (String global : globals) {
            jep.eval(moduleName + "." + global + " = " + global);
        }

        String taskInstancePerThread = moduleName + "_javaTaskPerThread";
        jep.set(taskInstancePerThread, new TaskInstancePerThread(moduleName, this));
        jep.eval(moduleName + ".javaTask" + " = " + taskInstancePerThread);

        jep.invoke(getInstanceMethod("init"), confParams, confDir);

        try {
            isEnabled = (Boolean) jep.invoke(getInstanceMethod("isEnabled"));

        } catch (JepException e) {
            if (e.toString().contains(" has no attribute ")) {
                isEnabled = true;
            } else {
                throw e;
            }
        }
    }

    public static class TaskInstancePerThread {

        private static Map<String, PythonTask> map = new ConcurrentHashMap<>();
        private String moduleName;

        private TaskInstancePerThread(String moduleName, PythonTask task) {
            this.moduleName = moduleName;
            map.put(moduleName + Thread.currentThread().getId(), task);
        }

        public PythonTask get() {
            return map.get(moduleName + Thread.currentThread().getId());
        }

    }

    private String getInstanceMethod(String function) {
        return instanceName + "." + function;
    }

    @Override
    public String getName() {
        return scriptFile.getName();
    }

    @Override
    public void init(Properties confParams, File confDir) throws Exception {
        this.confParams = confParams;
        this.confDir = confDir;
        try (Jep jep = getNewJep()) {
            loadScript(jep);

        } catch (JepException e) {
            if (jepException == null) {
                String msg = e.getMessage() + ". " + scriptFile.getName() + DISABLED + SEE_MANUAL;
                jepException = new JepException(msg, e);
                LOGGER.error(msg);
                jepException.printStackTrace();
            }
            isEnabled = false;
        }
        lastInstalledScript = scriptFile;
        numInstances++;
    }

    @Override
    public void finish() throws Exception {

        if (ipedCase == null) {
            ipedCase = new IPEDSource(this.output.getParentFile(), worker.writer);
        }

        if (isEnabled) {
            IPEDSearcher searcher = new IPEDSearcher(ipedCase);
            setModuleVar(getJep(), moduleName, "ipedCase", ipedCase); //$NON-NLS-1$
            setModuleVar(getJep(), moduleName, "searcher", searcher); //$NON-NLS-1$

            getJep().invoke(getInstanceMethod("finish")); //$NON-NLS-1$
        }

        if (--numInstances == 0) {
            ipedCase.close();
        }

        if (jepException == null && lastInstalledScript.equals(scriptFile)) {
            getJep().close();
        }

    }
    
    public void sendToNextTaskSuper(IItem item) throws Exception {
        super.sendToNextTask(item);
    }
    
    private boolean methodExists = true;
    
    @Override
    protected void sendToNextTask(IItem item) throws Exception {

        if (!isEnabled || !methodExists) {
            super.sendToNextTask(item);
            return;
        }
        try {
            getJep().invoke(getInstanceMethod("sendToNextTask"), item); //$NON-NLS-1$
            
        }catch(JepException e) {
            if (e.toString().contains(" has no attribute ")) {
                methodExists = false;
                super.sendToNextTask(item);
            } else {
                throw e;
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return isEnabled;
    }

    @Override
    protected boolean processQueueEnd() {
        if (processQueueEnd == null) {
            try {
                processQueueEnd = (Boolean) getJep().invoke(getInstanceMethod("processQueueEnd")); //$NON-NLS-1$
            } catch (JepException e) {
                processQueueEnd = false;
            }
        }
        return processQueueEnd;
    }

    @Override
    public void process(IItem item) throws Exception {

        if (jepException != null)
            throw jepException;

        try {
            getJep().invoke(getInstanceMethod("process"), item); //$NON-NLS-1$

        } catch (JepException e) {
            LOGGER.warn("Exception from " + getName() + " on " + item.getPath() + ": " + e.toString(), e);
            if (e.toString().toLowerCase().contains("invalid thread access")) {
                throw e;
            }
        }
    }

}
