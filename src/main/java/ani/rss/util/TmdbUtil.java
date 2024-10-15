package ani.rss.util;

import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class TmdbUtil {

    /**
     * 获取番剧在tmdb的名称
     *
     * @param name
     * @return
     */
    public synchronized static String getName(String name) {
        name = name.trim();
        if (StrUtil.isBlank(name)) {
            return "";
        }
        AtomicReference<String> year = new AtomicReference<>("");
        String yearReg = " \\((\\d{4})\\)$";
        if (ReUtil.contains(yearReg, name)) {
            year.set(ReUtil.get(yearReg, name, 1));
            name = name.replaceAll(yearReg, "");
        }
        if (StrUtil.isBlank(name)) {
            return "";
        }
        String themoviedbName;
        try {
            String finalName = name;
            themoviedbName = HttpReq.get("https://www.themoviedb.org/search", true)
                    .form("query", name)
                    .header("accept-language", "zh-CN")
                    .thenFunction(res -> {
                        org.jsoup.nodes.Document document = Jsoup.parse(res.body());
                        Element element = document.selectFirst(".title h2");
                        if (Objects.isNull(element)) {
                            if (!finalName.contains(" ")) {
                                return "";
                            }
                            ThreadUtil.sleep(500);
                            return getName(finalName.split(" ")[0]);
                        }
                        Element releaseDate = document.selectFirst(".release_date");
                        if (Objects.nonNull(releaseDate) && StrUtil.isNotBlank(year.get())) {
                            String s = "(\\d{4}) 年 \\d{2} 月 \\d{2} 日";
                            String text = releaseDate.text();
                            if (ReUtil.contains(s, text)) {
                                year.set(ReUtil.get(s, text, 1));
                            }
                        }
                        String title = element.ownText();
                        title = title.replace("1/2", "½");
                        var ls = List.of("/", "\\", ":", "?", "*", "|", ">", "<", "\"");
                        for (String l : ls) {
                            title = title.replace(l, " ");
                        }
                        title = title.trim();
                        return StrUtil.blankToDefault(title, "");
                    });
        } catch (Exception e) {
            String message = ExceptionUtil.getMessage(e);
            log.error(message, e);
            return "";
        }
        if (StrUtil.isBlank(themoviedbName)) {
            return "";
        }
        if (StrUtil.isNotBlank(year.get())) {
            themoviedbName = StrFormatter.format("{} ({})", themoviedbName, year);
        }
        return themoviedbName;
    }
}