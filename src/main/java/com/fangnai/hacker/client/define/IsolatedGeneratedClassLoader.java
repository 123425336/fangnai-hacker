package com.fangnai.hacker.client.define;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隔离的 ClassLoader，用于加载同名 generated class 的新版本。
 * 每次需要"redefine"一个已加载类时，创建一个新的 IsolatedGeneratedClassLoader 实例，
 * 在其中 define 新版本。旧版本的 Class 对象在没有引用后会被 GC。
 */
public final class IsolatedGeneratedClassLoader extends ClassLoader {
    private final Map<String, Class<?>> definedClasses = new ConcurrentHashMap<>();
    private final String targetPackage;

    static {
        registerAsParallelCapable();
    }

    /**
     * @param parent       父 ClassLoader，用于委托加载非 generated 类（Minecraft, Forge 等依赖）
     * @param targetPackage 允许加载的包名，例如 "com.fangnai.hacker.generated"
     */
    public IsolatedGeneratedClassLoader(ClassLoader parent, String targetPackage) {
        super(parent);
        this.targetPackage = targetPackage == null ? "" : targetPackage;
    }

    /**
     * 在此隔离 ClassLoader 中 define 一个类。
     *
     * @param className 类的全限定名
     * @param bytes     class 字节码
     * @return 新定义的 Class 对象
     * @throws SecurityException       如果类不在允许的包下
     * @throws IllegalArgumentException 如果类已在此 ClassLoader 中定义
     */
    public Class<?> defineIsolatedClass(String className, byte[] bytes) {
        if (!className.startsWith(targetPackage + ".")) {
            throw new SecurityException("此隔离 ClassLoader 只能 define 包 " + targetPackage + " 下的类，拒绝：" + className);
        }
        if (definedClasses.containsKey(className)) {
            throw new IllegalArgumentException("类已在此隔离 ClassLoader 中定义：" + className);
        }
        Class<?> defined = defineClass(className, bytes, 0, bytes.length);
        definedClasses.put(className, defined);
        return defined;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 如果是我们已 define 的 generated 类，直接返回
        Class<?> defined = definedClasses.get(name);
        if (defined != null) {
            if (resolve) {
                resolveClass(defined);
            }
            return defined;
        }

        // 如果是 generated 包下的其他类（内部类、辅助类），但还没 define，尝试从父加载器加载
        // 或者等待后续 defineBatch 时一起 define
        if (name.startsWith(targetPackage + ".")) {
            // 先检查父 ClassLoader 是否已有（可能是旧版本或其他工具类）
            try {
                return super.loadClass(name, resolve);
            } catch (ClassNotFoundException e) {
                // 如果父加载器没有，说明这是一个还没 define 的辅助类
                // 抛出异常，调用者应该在 batch define 时一起处理
                throw e;
            }
        }

        // 其他类（Minecraft, Forge, JDK 等）委托给父 ClassLoader
        return super.loadClass(name, resolve);
    }

    /**
     * @return 此 ClassLoader 已定义的 generated 类的数量
     */
    public int definedClassCount() {
        return definedClasses.size();
    }

    /**
     * @return 此 ClassLoader 的简短描述，用于日志
     */
    public String toShortString() {
        return "IsolatedGeneratedClassLoader@" + Integer.toHexString(System.identityHashCode(this))
                + "[defined=" + definedClassCount() + ", package=" + targetPackage + "]";
    }
}
