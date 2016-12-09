/*
 * Copyright 2015-present wequick.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package net.wequick.small;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Message;
import android.util.Log;
import android.view.Window;

import net.wequick.small.internal.InstrumentationInternal;
import net.wequick.small.util.ReflectAccelerator;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexFile;

/**
 * This class launch the plugin activity by it's class name.
 *  这个类用于启动插件activity通过类名
 * <p>This class resolve the bundle who's <tt>pkg</tt> is specified as
 * <i>"*.app.*"</i> or <i>*.lib.*</i> in <tt>bundle.json</tt>.
 *
 * <ul>
 * <li>The <i>app</i> plugin contains some activities usually, while launching,
 * takes the bundle's <tt>uri</tt> as default activity. the other activities
 * can be specified by the bundle's <tt>rules</tt>.</li>
 *
 * <li>The <i>lib</i> plugin which can be included by <i>app</i> plugin
 * consists exclusively of global methods that operate on your product services.</li>
 * </ul>
 *
 * @see ActivityLauncher
 */
public class ApkBundleLauncher extends SoBundleLauncher {

    private static final String PACKAGE_NAME = ApkBundleLauncher.class.getPackage().getName();
    private static final String STUB_ACTIVITY_PREFIX = PACKAGE_NAME + ".A";
    private static final String STUB_ACTIVITY_TRANSLUCENT = STUB_ACTIVITY_PREFIX + '1';
    private static final String TAG = "ApkBundleLauncher";
    private static final String FD_STORAGE = "storage";
    private static final String FILE_DEX = "bundle.dex";

    //LoadedApk类
    private static class LoadedApk {
        public String packageName;
        public File packagePath;
        public String applicationName;
        public String path;
        public DexFile dexFile;
        public File optDexFile;
        public File libraryPath;
        public boolean nonResources; /** no resources.arsc */
    }

    private static ConcurrentHashMap<String, LoadedApk> sLoadedApks;
    private static ConcurrentHashMap<String, ActivityInfo> sLoadedActivities;
    private static ConcurrentHashMap<String, List<IntentFilter>> sLoadedIntentFilters;

    protected static Instrumentation sHostInstrumentation;
    private static Instrumentation sBundleInstrumentation;

    private static final char REDIRECT_FLAG = '>';

    /**
     * Class for restore activity info from Stub to Real
     *该类主要实现activity从存根到插件的恢复
     */
    private static class ActivityThreadHandlerCallback implements Handler.Callback {

        private static final int LAUNCH_ACTIVITY = 100;

        @Override
        public boolean handleMessage(Message msg) {
            if (msg.what != LAUNCH_ACTIVITY) return false;

            Object/*ActivityClientRecord*/ r = msg.obj;
            //获取到r中的Intent对象
            Intent intent = ReflectAccelerator.getIntent(r);
            //使用unwrapIntent方法将插件的类名赋给targetClass
            String targetClass = unwrapIntent(intent);
            if (targetClass == null) return false;

            // 替换上插件类对应的activityInfo
            ActivityInfo targetInfo = sLoadedActivities.get(targetClass);
            ReflectAccelerator.setActivityInfo(r, targetInfo);
            return false;
        }
    }

    /**
     * Class for redirect activity from Stub(AndroidManifest.xml) to Real(Plugin)
     * 从存根（AndroidManifest中注册过）的到真实的（插件）类的重定位activity
     */
    private static class InstrumentationWrapper extends Instrumentation
            implements InstrumentationInternal {

        private static final int STUB_ACTIVITIES_COUNT = 4;

        public InstrumentationWrapper() { }

        /** @Override V21+
         * Wrap activity from REAL to STUB */
        /**插件activity到存根activity的包装 */
        /*  execStartActivity函数在不同android版本中的参数有所不同，
        把PluginActivity1的Intent替换成了ProxyActivity$1的Intent。
        最后在ReflectAccelerator中完成原来函数的调用。*/
        public ActivityResult execStartActivity(
                Context who, IBinder contextThread, IBinder token, Activity target,
                Intent intent, int requestCode, android.os.Bundle options) {
//           wrapIntent函数完成了替换Intent的动作
            wrapIntent(intent);
            return ReflectAccelerator.execStartActivity(sHostInstrumentation,
                    who, contextThread, token, target, intent, requestCode, options);
        }

        /** @Override V20-
         * Wrap activity from REAL to STUB */
        /**插件activity到存根activity的包装 */
        public ActivityResult execStartActivity(
                Context who, IBinder contextThread, IBinder token, Activity target,
                Intent intent, int requestCode) {
//           wrapIntent函数完成了替换Intent的动作
            wrapIntent(intent);
            return ReflectAccelerator.execStartActivity(sHostInstrumentation,
                    who, contextThread, token, target, intent, requestCode);
        }

        @Override
        /** Prepare resources for REAL */
        /**为插件准备资源*/
        public void callActivityOnCreate(Activity activity, android.os.Bundle icicle) {
            do {
                if (sLoadedActivities == null) break;
                ActivityInfo ai = sLoadedActivities.get(activity.getClass().getName());
                if (ai == null) break;
                //同步插件activity对应的窗口信息
                applyActivityInfo(activity, ai);
            } while (false);
            //调用原Instrumentation的callActivityOnCreate方法
            sHostInstrumentation.callActivityOnCreate(activity, icicle);

            // Reset activity instrumentation if it was modified by some other applications #245
            //如果它被其他的应用程序修改了一些信息,复位原先的activity instrumentation
            if (sBundleInstrumentation != null) {
                try {
                    Field f = Activity.class.getDeclaredField("mInstrumentation");
                    f.setAccessible(true);
                    Object instrumentation = f.get(activity);
                    if (instrumentation != sBundleInstrumentation) {
                        f.set(activity, sBundleInstrumentation);
                    }
                } catch (NoSuchFieldException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void callActivityOnStop(Activity activity) {
            sHostInstrumentation.callActivityOnStop(activity);

            if (!Small.isUpgrading()) return;

            // If is upgrading, we are going to kill self while application turn into background,
            // and while we are back to foreground, all the things(code & layout) will be reload.
            // Don't worry about the data missing in current activity, you can do all the backups
            // with your activity's `onSaveInstanceState' and `onRestoreInstanceState'.

            // Get all the processes of device (1)
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            List<RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes == null) return;

            // Gather all the processes of current application (2)
            // Above 5.1.1, this may be equals to (1), on the safe side, we also
            // filter the processes with current package name.
            String pkg = activity.getApplicationContext().getPackageName();
            final List<RunningAppProcessInfo> currentAppProcesses = new ArrayList<>(processes.size());
            for (RunningAppProcessInfo p : processes) {
                if (p.pkgList == null) continue;

                boolean match = false;
                int N = p.pkgList.length;
                for (int i = 0; i < N; i++) {
                    if (p.pkgList[i].equals(pkg)) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;

                currentAppProcesses.add(p);
            }
            if (currentAppProcesses.isEmpty()) return;

            // The top process of current application processes.
            RunningAppProcessInfo currentProcess = currentAppProcesses.get(0);
            if (currentProcess.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return;

            // Seems should delay some time to ensure the activity can be successfully
            // restarted after the application restart.
            // FIXME: remove following thread if you find the better place to `killProcess'

            new Thread() {
                @Override
                public void run() {
                    try {
                        sleep(300);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    for (RunningAppProcessInfo p : currentAppProcesses) {
                        android.os.Process.killProcess(p.pid);
                    }
                }
            }.start();
        }

        @Override
        public void callActivityOnDestroy(Activity activity) {
            do {
                if (sLoadedActivities == null) break;
                String realClazz = activity.getClass().getName();
                ActivityInfo ai = sLoadedActivities.get(realClazz);
                if (ai == null) break;
                //存根activity与真实activity解绑
                inqueueStubActivity(ai, realClazz);
            } while (false);
            //调用原Instrumentation的callActivityOnDestroy方法
            sHostInstrumentation.callActivityOnDestroy(activity);
        }

        /*wrapIntent方法将插件activity类保存在intent的category中，
        同时将intent的component里面的类替换为 host中声明的占位Activity，
        以通过ActivityManager的检查*/
        private void wrapIntent(Intent intent) {
            ComponentName component = intent.getComponent();
            String realClazz;
            if (component == null) {
                // 隐式方法启动一个Actvity
                /**隐式，即不是像显式的那样直接指定需要调用的Activity，隐式不明确指定启动哪个Activity，
                 * 而是设置Action、Data、Category，让系统来筛选出合适的Activity。
                 * 筛选是根据所有的<intent-filter>来筛选。*/
            //向component传入包管理器，可以对包管理器进行查询以确定是否有Activity能够启动该Intent：
                component = intent.resolveActivity(Small.getContext().getPackageManager());
                if (component != null) return; // ignore system or host action
                realClazz = resolveActivity(intent);
                if (realClazz == null) return;
            } else {
            //获得插件类对象
                realClazz = component.getClassName();
            }

            if (sLoadedActivities == null) return;
            //启动activities是这个插件类对象的
            ActivityInfo ai = sLoadedActivities.get(realClazz);
            if (ai == null) return;

            // Carry the real(plugin) class for incoming `newActivity' method.
            //向该插件类中添加到intent的一个名为REDIRECT_FLAG的Category
            intent.addCategory(REDIRECT_FLAG + realClazz);
            //从插件Activity类对应变换到存根的Activity类
            String stubClazz = dequeueStubActivity(ai, realClazz);
            intent.setComponent(new ComponentName(Small.getContext(), stubClazz));
        }
        //根据intent找到跳转的插件Activity
        private String resolveActivity(Intent intent) {
            if (sLoadedIntentFilters == null) return null;

            Iterator<Map.Entry<String, List<IntentFilter>>> it =
                    sLoadedIntentFilters.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, List<IntentFilter>> entry = it.next();
                List<IntentFilter> filters = entry.getValue();
                for (IntentFilter filter : filters) {
                    if (filter.hasAction(Intent.ACTION_VIEW)) {
                        // TODO: match uri
                    }
                    if (filter.hasCategory(Intent.CATEGORY_DEFAULT)) {
                        // custom action
                        if (filter.hasAction(intent.getAction())) {
                            // hit
                            return entry.getKey();
                        }
                    }
                }
            }
            return null;
        }

        private String[] mStubQueue;

        /** Get an usable stub activity clazz from real activity */
        /**从真实Activity得到一个可用的存根Activity*/
        private String dequeueStubActivity(ActivityInfo ai, String realActivityClazz) {

            //判断插件Activity的launchMode是否是standard，是就返回launchMode是standard的存根Activity
            if (ai.launchMode == ActivityInfo.LAUNCH_MULTIPLE) {
                // In standard mode, the stub activity is reusable.在标准模式下，存根Activity是可重复使用的。
                // Cause the `windowIsTranslucent' attribute cannot be dynamically set,由于` windowistranslucent”属性不能动态设置
                // We should choose the STUB activity with translucent or not here.我们应该选择的存根activity是半透明或不是
                Resources.Theme theme = Small.getContext().getResources().newTheme();
                theme.applyStyle(ai.getThemeResource(), true);
                TypedArray sa = theme.obtainStyledAttributes(
                        new int[] { android.R.attr.windowIsTranslucent });
                boolean translucent = sa.getBoolean(0, false);
                sa.recycle();
                //判断插件Activity是否是Translucent主题的，并使用相应的存根Activity
                return translucent ? STUB_ACTIVITY_TRANSLUCENT : STUB_ACTIVITY_PREFIX;
            }

            int availableId = -1;
            int stubId = -1;
            int countForMode = STUB_ACTIVITIES_COUNT;
            int countForAll = countForMode * 3; // 3=[singleTop, singleTask, singleInstance]
            if (mStubQueue == null) {
                // 简单初始化字符串数组
                mStubQueue = new String[countForAll];
            }
            int offset = (ai.launchMode - 1) * countForMode;
            for (int i = 0; i < countForMode; i++) {
                String usedActivityClazz = mStubQueue[i + offset];
                if (usedActivityClazz == null) {
                    if (availableId == -1) availableId = i;
                } else if (usedActivityClazz.equals(realActivityClazz)) {
                    //判断这个插件Activity是否已经使用过存根的Activity
                    stubId = i;
                }
            }
            if (stubId != -1) {
                //是使用过存根的Activity就返回其对应的存根Activity
                availableId = stubId;
            } else if (availableId != -1) {
                mStubQueue[availableId + offset] = realActivityClazz;
            } else {
                // TODO:
                Log.e(TAG, "Launch mode " + ai.launchMode + " is full");
            }
            //返回的数据格式是：PACKAGE_NAME +.A+{1,2,3}+{0,1,2,3};就Manifest中对应的存根Activity
            return STUB_ACTIVITY_PREFIX + ai.launchMode + availableId;
        }

        /** Unbind the stub activity from real activity */
        /** 将存根activity与真实activity解绑*/
        private void inqueueStubActivity(ActivityInfo ai, String realActivityClazz) {
            if (ai.launchMode == ActivityInfo.LAUNCH_MULTIPLE) return;
            if (mStubQueue == null) return;
            int countForMode = STUB_ACTIVITIES_COUNT;
            int offset = (ai.launchMode - 1) * countForMode;
            for (int i = 0; i < countForMode; i++) {
                String stubClazz = mStubQueue[i + offset];
                if (stubClazz != null && stubClazz.equals(realActivityClazz)) {
                    mStubQueue[i + offset] = null;
                    break;
                }
            }
        }
    }


    private static String unwrapIntent(Intent intent) {
        //得到该intent对象中的所有category
        Set<String> categories = intent.getCategories();
        if (categories == null) return null;

        //通过查找categories其中名为REDIRECT_FLAG的category来找到原来插件activity类名
        Iterator<String> it = categories.iterator();
        while (it.hasNext()) {
            String category = it.next();
            if (category.charAt(0) == REDIRECT_FLAG) {
                //返回对应插件activity类名
                return category.substring(1);
            }
        }
        return null;
    }

    /**
     * A context wrapper that redirect some host environments to plugin
     *
     */
    private static final class BundleApplicationContext extends ContextWrapper {

        private LoadedApk mApk;

        public BundleApplicationContext(Context base, LoadedApk apk) {
            super(base);
            mApk = apk;
        }

        @Override
        public String getPackageName() {
            return mApk.packageName;
        }

        @Override
        public String getPackageResourcePath() {
            return mApk.path;
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            ApplicationInfo ai = super.getApplicationInfo();
            // TODO: Read meta-data in bundles and merge to the host one
            // ai.metaData.putAll();
            return ai;
        }
    }

    /**
     * 该方法注入instrumentation，为解决Activity的启动和生命周期问题做准备。
     * */
    /**　ActivityThread中有一个单例对象sCurrentActivityThread，它是在attche时被赋值的。
    Instrumentation在ActivityThread中的应用是mInstrumentation，
    所以我们先通过ActivityThread的静态方法currentActivityThread得到它的实例sCurrentActivityThread，
    然后找到它的成员mInstrumentation，把它的引用对象换成自己实现的InstrumentationWrapper。
    InstrumentationWrapper也是Instrumentation类型，它里面实现了execStartActivity和newActivity这两个方法，
    用来替换掉原来的函数调用。*/
    @Override
    public void setUp(Context context) {
        //ApkBundleLauncher重写了setUp方法,替换掉ActivityThread的mInstrumentation成员变量
        super.setUp(context);
        if (sHostInstrumentation == null) {
            try {
                //反射获取ActivityThread类
                final Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                //获取到activityThread对象
                Object thread = ReflectAccelerator.getActivityThread(context, activityThreadClass);
                //反射获取到ActivityThread类中mInstrumentation成员变量
                Field field = activityThreadClass.getDeclaredField("mInstrumentation");
                //开启访问私有权限
                field.setAccessible(true);
                //取得activityThread对象中mInstrumentation成员变量的属性值
                sHostInstrumentation = (Instrumentation) field.get(thread);

                //实例化自己定义的InstrumentationWrapper()对象
                Instrumentation wrapper = new InstrumentationWrapper();
                //将activityThread对象中mInstrumentation属性的属性值赋值成为InstrumentationWrapper实例对象
                field.set(thread, wrapper);
                if (!sHostInstrumentation.getClass().getName().equals("android.app.Instrumentation")) {
                    sBundleInstrumentation = wrapper; // record for later replacement
                }
                //instanceof是java里面的二元运算符，判断左边的对象是否是右边类的实例。假如是的话，返回true；
                // 假如不是的话，返回false。
                if (context instanceof Activity) {
                    field = Activity.class.getDeclaredField("mInstrumentation");
                    field.setAccessible(true);
                    field.set(context, wrapper);
                }

                // 反射获取ActivityThread类中mH成员变量
                field = activityThreadClass.getDeclaredField("mH");
                field.setAccessible(true);
                //获得activityThread对象中mH成员变量的属性值
                Handler ah = (Handler) field.get(thread);

                //反射获取Handler类中mCallback成员变量
                field = Handler.class.getDeclaredField("mCallback");
                field.setAccessible(true);
                //向ah对象中赋值为ActivityThreadHandlerCallback()
                //此刻的field是mCallback成员变量，
                // 下面就将ActivityThreadHandlerCallback的实例赋给mCallback变量
                field.set(ah, new ActivityThreadHandlerCallback());
            } catch (Exception ignored) {
                ignored.printStackTrace();
                // Usually, cannot reach here
            }
        }
    }

    @Override
    public void postSetUp() {
        super.postSetUp();

        if (sLoadedApks == null) {
            Log.e(TAG, "Could not find any APK bundles!");
            return;
        }

        Collection<LoadedApk> apks = sLoadedApks.values();

        // Merge all the resources in bundles and replace the host one
        final Application app = Small.getContext();
        String[] paths = new String[apks.size() + 1];
        paths[0] = app.getPackageResourcePath(); // add host asset path
        int i = 1;
        for (LoadedApk apk : apks) {
            if (apk.nonResources) continue; // ignores the empty entry to fix #62
            paths[i++] = apk.path; // add plugin asset path
        }
        if (i != paths.length) {
            paths = Arrays.copyOf(paths, i);
        }
        ReflectAccelerator.mergeResources(app, paths);

        // Merge all the dex into host's class loader
        //获取宿主类加载器
        ClassLoader cl = app.getClassLoader();
        i = 0;
        int N = apks.size();
        String[] dexPaths = new String[N];
        DexFile[] dexFiles = new DexFile[N];
        for (LoadedApk apk : apks) {
            dexPaths[i] = apk.path;
            dexFiles[i] = apk.dexFile;
            if (Small.getBundleUpgraded(apk.packageName)) {
                // If upgraded, delete the opt dex file for recreating
                if (apk.optDexFile.exists()) apk.optDexFile.delete();
                Small.setBundleUpgraded(apk.packageName, false);
            }
            i++;
        }
        //扩展DexPathList
        ReflectAccelerator.expandDexPathList(cl, dexPaths, dexFiles);

        // Expand the native library directories for host class loader if plugin has any JNIs. (#79)
        List<File> libPathList = new ArrayList<File>();
        for (LoadedApk apk : apks) {
            if (apk.libraryPath != null) {
                libPathList.add(apk.libraryPath);
            }
        }
        if (libPathList.size() > 0) {
            ReflectAccelerator.expandNativeLibraryDirectories(cl, libPathList);
        }

        // Trigger all the bundle application `onCreate' event
        for (final LoadedApk apk : apks) {
            String bundleApplicationName = apk.applicationName;
            if (bundleApplicationName == null) continue;

            try {
                final Class applicationClass = Class.forName(bundleApplicationName);
                Bundle.postUI(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            BundleApplicationContext appContext = new BundleApplicationContext(app, apk);
                            Application bundleApplication = Instrumentation.newApplication(
                                    applicationClass, appContext);
                            sHostInstrumentation.callApplicationOnCreate(bundleApplication);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Free temporary variables
        sLoadedApks = null;
    }

    @Override
    protected String[] getSupportingTypes() {
        return new String[] {"app", "lib"};
    }

    @Override
    public File getExtractPath(Bundle bundle) {
        Context context = Small.getContext();
        //获取路径：/data/data/宿主包名/files/storage
        File packagePath = context.getFileStreamPath(FD_STORAGE);
        //返回路径：/data/data/宿主包名/files/storage/插件包名
        return new File(packagePath, bundle.getPackageName());
    }

    @Override
    public File getExtractFile(Bundle bundle, String entryName) {
        if (!entryName.endsWith(".so")) return null;

        return new File(bundle.getExtractPath(), entryName);
    }

    //判断插件是否能加载，能加载就启动activity；
    @Override
    public void loadBundle(Bundle bundle) {
        //获取包名
        String packageName = bundle.getPackageName();

        BundleParser parser = bundle.getParser();
        parser.collectActivities();
        PackageInfo pluginInfo = parser.getPackageInfo();

        // Load the bundle
        //data/app/宿主包名/lib/arm/.so
        String apkPath = parser.getSourcePath();
        if (sLoadedApks == null) sLoadedApks = new ConcurrentHashMap<String, LoadedApk>();
        LoadedApk apk = sLoadedApks.get(packageName);
        if (apk == null) {
            apk = new LoadedApk();
            //包名
            apk.packageName = packageName;
            apk.path = apkPath;
            apk.nonResources = parser.isNonResources();
            if (pluginInfo.applicationInfo != null) {
                apk.applicationName = pluginInfo.applicationInfo.className;
            }
            //data/data/宿主包名/files/storage/插件包名
            apk.packagePath = bundle.getExtractPath();
            //data/data/宿主包名/files/storage/插件包名/bundle.dex
            apk.optDexFile = new File(apk.packagePath, FILE_DEX);

            // Load dex
            final LoadedApk fApk = apk;
            Bundle.postIO(new Runnable() {
                @Override
                public void run() {
                    try {
                        //从so文件load dex文件
                        fApk.dexFile = DexFile.loadDex(fApk.path, fApk.optDexFile.getPath(), 0);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            // Extract native libraries with specify ABI
            String libDir = parser.getLibraryDirectory();
            if (libDir != null) {
                apk.libraryPath = new File(apk.packagePath, libDir);
            }
            sLoadedApks.put(packageName, apk);
        }

        if (pluginInfo.activities == null) {
            bundle.setLaunchable(false);
            return;
        }

        // Record activities for intent redirection
        if (sLoadedActivities == null) sLoadedActivities = new ConcurrentHashMap<String, ActivityInfo>();
        for (ActivityInfo ai : pluginInfo.activities) {
            sLoadedActivities.put(ai.name, ai);
        }

        // Record intent-filters for implicit action
        ConcurrentHashMap<String, List<IntentFilter>> filters = parser.getIntentFilters();
        if (filters != null) {
            if (sLoadedIntentFilters == null) {
                sLoadedIntentFilters = new ConcurrentHashMap<String, List<IntentFilter>>();
            }
            sLoadedIntentFilters.putAll(filters);
        }

        // Set entrance activity
        bundle.setEntrance(parser.getDefaultActivityName());
    }

    //准备Bundle的一些必要信息：一般是生成bundle的intent信息，主要是要启动的类名的生成；
    @Override
    public void prelaunchBundle(Bundle bundle) {
        super.prelaunchBundle(bundle);
        Intent intent = new Intent();
        bundle.setIntent(intent);

        // Intent extras - class
        String activityName = bundle.getActivityName();
        if (!ActivityLauncher.containsActivity(activityName)) {
            if (!sLoadedActivities.containsKey(activityName)) {
                if (activityName.endsWith("Activity")) {
                    throw new ActivityNotFoundException("Unable to find explicit activity class " +
                            "{ " + activityName + " }");
                }

                String tempActivityName = activityName + "Activity";
                if (!sLoadedActivities.containsKey(tempActivityName)) {
                    throw new ActivityNotFoundException("Unable to find explicit activity class " +
                            "{ " + activityName + "(Activity) }");
                }

                activityName = tempActivityName;
            }
        }
        intent.setComponent(new ComponentName(Small.getContext(), activityName));

        // Intent extras - params
        String query = bundle.getQuery();
        if (query != null) {
            intent.putExtra(Small.KEY_QUERY, '?'+query);
        }
    }

    @Override
    public void launchBundle(Bundle bundle, Context context) {
        prelaunchBundle(bundle);
        super.launchBundle(bundle, context);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    public <T> T createObject(Bundle bundle, Context context, String type) {
        if (type.startsWith("fragment")) {
            if (!(context instanceof Activity)) {
                return null; // context should be an activity which can be add resources asset path
            }
            String packageName = bundle.getPackageName();
            if (packageName == null) return null;
            String fname = bundle.getPath();
            if (fname == null || fname.equals("")) {
                fname = packageName + ".MainFragment"; // default
            } else {
                char c = fname.charAt(0);
                if (c == '.') {
                    fname = packageName + fname;
                } else if (c >= 'A' && c <= 'Z') {
                    fname = packageName + "." + fname;
                } else {
                    // TODO: check the full quality fragment class name
                }
            }
            if (type.endsWith("v4")) {
                return (T) android.support.v4.app.Fragment.instantiate(context, fname);
            }
            return (T) android.app.Fragment.instantiate(context, fname);
        }
        return super.createObject(bundle, context, type);
    }

    /**
     * Apply plugin activity info with plugin's AndroidManifest.xml
     * 申请的插件Activity信息是与插件的AndroidManifest.xml里面一样
     * @param activity
     * @param ai
     */
    private static void applyActivityInfo(Activity activity, ActivityInfo ai) {
        // Apply window attributes
        Window window = activity.getWindow();
        window.setSoftInputMode(ai.softInputMode);
        activity.setRequestedOrientation(ai.screenOrientation);
    }
}
