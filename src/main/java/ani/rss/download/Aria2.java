package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.util.HttpReq;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aria2
 */
@Slf4j
public class Aria2 implements BaseDownload {
    private Config config;

    @Override
    public Boolean login(Config config) {
        this.config = config;
        String host = config.getHost();
        String password = config.getPassword();

        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("Aria2 未配置完成");
            return false;
        }

        String body = ResourceUtil.readUtf8Str("aria2/getGlobalStat.json");
        body = StrFormatter.format(body, password);
        return HttpReq.post(host + "/jsonrpc", false)
                .body(body)
                .thenFunction(HttpResponse::isOk);
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        List<TorrentsInfo> torrentsInfos = new ArrayList<>();
        ThreadUtil.sleep(1000);
        torrentsInfos.addAll(getTorrentsInfos("aria2/tellActive.json"));
        torrentsInfos.addAll(getTorrentsInfos("aria2/tellStopped.json"));
        return torrentsInfos;
    }

    public List<TorrentsInfo> getTorrentsInfos(String type) {
        String host = config.getHost();
        String password = config.getPassword();
        String body = ResourceUtil.readUtf8Str(type);
        body = StrFormatter.format(body, password);
        return HttpReq.post(host + "/jsonrpc", false)
                .body(body)
                .thenFunction(res -> {
                    JsonObject jsonObject = gson.fromJson(res.body(), JsonObject.class);
                    List<JsonElement> result = jsonObject.get("result").getAsJsonArray().asList();
                    List<TorrentsInfo> torrentsInfos = new ArrayList<>();
                    for (JsonElement jsonElement : result) {
                        JsonObject asJsonObject = jsonElement.getAsJsonObject();
                        JsonElement bittorrent = asJsonObject.get("bittorrent");
                        if (bittorrent.isJsonNull()) {
                            continue;
                        }
                        JsonElement info = bittorrent.getAsJsonObject()
                                .get("info");
                        if (Objects.isNull(info)) {
                            continue;
                        }
                        String name = info.getAsJsonObject()
                                .get("name").getAsString();
                        String infoHash = asJsonObject.get("infoHash").getAsString();
                        String status = asJsonObject.get("status").getAsString();
                        TorrentsInfo.State state = "complete".equals(status) ?
                                TorrentsInfo.State.pausedUP : TorrentsInfo.State.downloading;
                        String dir = asJsonObject.get("dir").getAsString();
                        String gid = asJsonObject.get("gid").getAsString();

                        List<String> files = asJsonObject.get("files")
                                .getAsJsonArray()
                                .asList()
                                .stream().map(JsonElement::getAsJsonObject)
                                .map(o -> o.get("path").getAsString())
                                .collect(Collectors.toList());

                        TorrentsInfo torrentsInfo = new TorrentsInfo();
                        torrentsInfo
                                .setTags("")
                                .setId(gid)
                                .setName(name)
                                .setHash(infoHash)
                                .setState(state)
                                .setDownloadDir(dir)
                                .setFiles(files);
                        torrentsInfos.add(torrentsInfo);
                    }
                    return torrentsInfos;
                });
    }


    @Override
    public Boolean download(Item item, String savePath, File torrentFile, Boolean ova) {
        String name = item.getReName();
        String host = config.getHost();
        String password = config.getPassword();
        String body = "";

        String extName = FileUtil.extName(torrentFile);
        if (StrUtil.isBlank(extName)) {
            return false;
        }

        if ("txt".equals(extName)) {
            log.error("Aria2 暂不支持磁力链接下载与重命名");
            return false;
//            body = ResourceUtil.readUtf8Str("aria2/addUri.json");
//            body = StrFormatter.format(body, password, FileUtil.readUtf8String(torrentFile), savePath);
        } else {
            body = ResourceUtil.readUtf8Str("aria2/addTorrent.json");
            body = StrFormatter.format(body, password, Base64.encode(torrentFile), savePath);
        }

        String id = HttpReq.post(host + "/jsonrpc", false)
                .body(body)
                .thenFunction(res -> gson.fromJson(res.body(), JsonObject.class).get("result").getAsString());

        Boolean watchErrorTorrent = config.getWatchErrorTorrent();

        if (!watchErrorTorrent) {
            if (!ova) {
                renameCache.put(id, name);
            }
            return true;
        }

        for (int i = 0; i < 10; i++) {
            ThreadUtil.sleep(2000);
            List<TorrentsInfo> torrentsInfos = getTorrentsInfos();
            for (TorrentsInfo torrentsInfo : torrentsInfos) {
                if (!torrentsInfo.getId().equals(id)) {
                    continue;
                }
                if (!ova) {
                    renameCache.put(id, name);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void delete(TorrentsInfo torrentsInfo) {
        String host = config.getHost();
        String password = config.getPassword();
        String id = torrentsInfo.getId();
        String body = ResourceUtil.readUtf8Str("aria2/removeDownloadResult.json");
        body = StrFormatter.format(body, password, id);

        HttpReq.post(host + "/jsonrpc", false)
                .body(body)
                .thenFunction(HttpResponse::isOk);
    }

    @Override
    public void rename(TorrentsInfo torrentsInfo) {
        String id = torrentsInfo.getId();
        String downloadDir = torrentsInfo.getDownloadDir();
        String reName = renameCache.get(id);
        if (StrUtil.isBlank(reName)) {
            return;
        }
        Integer renameMinSize = config.getRenameMinSize();

        List<File> files = torrentsInfo.getFiles()
                .stream()
                .map(File::new)
                .filter(File::exists)
                .filter(file -> {
                    String extName = FileUtil.extName(file);
                    if (StrUtil.isBlank(extName)) {
                        return false;
                    }
                    if (file.length() < 1) {
                        return false;
                    }
                    return videoFormat.contains(extName) || subtitleFormat.contains(extName);
                })
                .sorted(Comparator.comparingLong(file -> Long.MAX_VALUE - file.length()))
                .collect(Collectors.toList());

        Assert.notEmpty(files);

        long videoCount = files.stream()
                .filter(file -> {
                    String extName = FileUtil.extName(file);
                    return videoFormat.contains(extName);
                }).count();

        List<File> newFiles = files
                .stream()
                .filter(file -> {
                    String extName = FileUtil.extName(file);
                    // 排除字幕
                    if (subtitleFormat.contains(extName)) {
                        return true;
                    }

                    // 大小限制为0时不启用
                    if (renameMinSize < 1) {
                        return true;
                    }
                    return renameMinSize <= file.length() / 1024 / 1024;
                }).collect(Collectors.toList());

        if (!newFiles.isEmpty() && videoCount > 1) {
            files = newFiles;
        }

        for (File src : files) {
            String name = src.getName();
            String fileReName = getFileReName(name, reName);
            File newPath = new File(downloadDir + "/" + fileReName);
            if (FileUtil.equals(src, newPath)) {
                continue;
            }
            FileUtil.move(src, newPath, false);
            log.info("重命名 {} ==> {}", name, newPath);
        }
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        return false;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        String trackersStr = CollUtil.join(trackers, "\\n");
        String host = config.getHost();
        String password = config.getPassword();
        String body = ResourceUtil.readUtf8Str("aria2/changeGlobalOption.json");
        body = StrFormatter.format(body, password, trackersStr);

        HttpReq.post(host + "/jsonrpc", false)
                .body(body)
                .then(res -> {
                    if (res.isOk()) {
                        log.info("Aria2 更新Trackers完成 共{}条", trackers.size());
                        return;
                    }
                    log.error("Aria2 更新Trackers失败 {}", res.getStatus());
                });
    }
}
