import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 逐个加载发行包里 Piko 自己的类，让 JVM 校验字节码，有 VerifyError 即以非零退出。
 *
 * release 变体经 ProGuard 改写，其预校验器可能算错栈帧：Bilby 在同样的打包流程上遇到过
 * ProGuard 7.8 与 7.10 为一个 Compose 函数写出非法 stack map，装上后一进该页即 VerifyError。
 * gradle run 与单测都不经过 ProGuard，发现不了。只查 dev.piko 下的类：依赖虽也经 ProGuard 裁剪，
 * 但 Piko 自己的代码是改动最频繁、最可能触发该问题的部分。
 *
 * 用法（单文件源码直接运行）：java -Xverify:all desktopApp/package/VerifyClasses.java <应用目录>/app
 */
public class VerifyClasses {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        List<URL> urls = new ArrayList<>();
        for (File f : dir.listFiles()) {
            if (f.getName().endsWith(".jar")) urls.add(f.toURI().toURL());
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        int checked = 0;
        int failed = 0;
        for (URL url : urls) {
            try (JarFile jar = new JarFile(new File(url.toURI()))) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    if (!name.startsWith("dev/piko/") || !name.endsWith(".class")) continue;
                    checked++;
                    String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    try {
                        // 校验发生在链接时。只 forName 不初始化不会链接，取方法表才会
                        Class.forName(className, false, loader).getDeclaredMethods();
                    } catch (VerifyError error) {
                        failed++;
                        System.out.println("VerifyError " + className + ": " + error.getMessage().lines().findFirst().orElse(""));
                    } catch (Throwable ignored) {
                        // 缺少只在特定平台存在的依赖之类，与字节码是否合法无关
                    }
                }
            }
        }
        System.out.println("checked " + checked + " classes, " + failed + " failed verification");
        if (checked == 0 || failed > 0) System.exit(1);
    }
}
