package ani.rss.util;

import ani.rss.Main;
import ani.rss.entity.About;
import cn.hutool.core.comparator.VersionComparator;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class UpdateUtil {
    public static About about() {
        String version = MavenUtil.getVersion();

        About about = new About()
                .setVersion(version)
                .setUpdate(false)
                .setLatest("")
                .setMarkdownBody("");
        try {
            HttpReq.get("https://github.com/wushuo894/ani-rss/releases/latest", true)
                    .timeout(3000)
                    .then(response -> {
                        String body = response.body();
                        Document document = Jsoup.parse(body);
                        Element box = document.selectFirst(".Box");
                        Element element = box.selectFirst("h1");
                        String latest = element.text().replace("v", "").trim();
                        about.setUpdate(VersionComparator.INSTANCE.compare(latest, version) > 0)
                                .setLatest(latest);
                        Element markdownBody = box.selectFirst(".markdown-body");
                        about.setMarkdownBody(markdownBody.html());
                        String filename = "ani-rss-jar-with-dependencies.jar";
                        File jar = getJar();
                        if ("exe".equals(FileUtil.extName(jar))) {
                            filename = "ani-rss-launcher.exe";
                        }
                        String downloadUrl = StrFormatter.format("https://github.com/wushuo894/ani-rss/releases/download/v{}/{}", latest, filename);
                        about.setDownloadUrl(downloadUrl);

                        try {
                            Element relativeTime = box.selectFirst("relative-time");
                            String datetime = relativeTime.attr("datetime");
                            about.setDate(DateUtil.parse(datetime));
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            String message = ExceptionUtil.getMessage(e);
            log.error("检测更新失败 {}", message);
            log.error(message, e);
        }
        return about;
    }

    public static void update(About about) {
        Boolean update = about.getUpdate();
        if (!update) {
            return;
        }
        File jar = getJar();
        String extName = StrUtil.blankToDefault(FileUtil.extName(jar), "");
        if (!List.of("jar", "exe").contains(extName)) {
            throw new RuntimeException("不支持更新");
        }
        File file = new File(jar + ".tmp");

        FileUtil.del(file);
        String downloadUrl = about.getDownloadUrl();
        String downloadMd5 = HttpReq.get(downloadUrl + ".md5", true)
                .thenFunction(res -> {
                    int status = res.getStatus();
                    Assert.isTrue(res.isOk(), "Error: {}", status);
                    String md5 = res.body();
                    if (StrUtil.isNotBlank(md5)) {
                        md5 = md5.split("\n")[0].trim();
                    }
                    Assert.isTrue(Md5Util.isValidMD5(md5), "获取更新文件MD5失败");
                    return md5;
                });
        HttpReq.get(downloadUrl, true)
                .then(res -> {
                    int status = res.getStatus();
                    Assert.isTrue(res.isOk(), "Error: {}", status);
                    long contentLength = res.contentLength();
                    FileUtil.writeFromStream(res.bodyStream(), file, true);
                    if (contentLength != file.length()) {
                        log.error("下载出现问题");
                        throw new RuntimeException("下载出现问题");
                    }

                    if (!Md5Util.digestHex(file).equals(downloadMd5)) {
                        log.error("更新文件的MD5不匹配");
                        throw new RuntimeException("更新文件的MD5不匹配");
                    }

                    ThreadUtil.execute(() -> {
                        ThreadUtil.sleep(1000);
                        if ("jar".equals(extName)) {
                            FileUtil.rename(file, jar.getName(), true);
                            ServerUtil.stop();
                            System.exit(0);
                            return;
                        }
                        String filename = "ani-rss-update.exe";
                        File updateExe = new File(file.getParent() + "/" + filename);
                        FileUtil.del(updateExe);
                        try (InputStream stream = ResourceUtil.getStream(filename)) {
                            FileUtil.writeFromStream(stream, updateExe, true);
                            ServerUtil.stop();
                            List<String> strings = new ArrayList<>();
                            strings.add(updateExe.toString());
                            strings.add(FileUtil.getAbsolutePath(jar));
                            strings.addAll(Main.ARGS);
                            String[] array = ArrayUtil.toArray(strings, String.class);
                            RuntimeUtil.exec(array);
                            System.exit(0);
                        } catch (Exception e) {
                            log.error(e.getMessage(), e);
                        }
                    });
                });
    }

    public static File getJar() {
        return new File(System.getProperty("java.class.path").split(";")[0]);
    }

}
