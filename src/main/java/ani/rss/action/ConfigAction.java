package ani.rss.action;

import ani.rss.annotation.Path;
import ani.rss.entity.Config;
import ani.rss.entity.Result;
import ani.rss.util.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.http.server.HttpServerRequest;
import cn.hutool.http.server.HttpServerResponse;
import cn.hutool.http.server.action.Action;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;

@Path("/config")
public class ConfigAction implements Action {
    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    @Override
    public void doAction(HttpServerRequest req, HttpServerResponse res) throws IOException {
        String method = req.getMethod();
        res.setContentType("application/json; charset=utf-8");
        if (method.equals("GET")) {
            String json = gson.toJson(Result.success(ConfigUtil.getCONFIG()));
            IoUtil.writeUtf8(res.getOut(), true, json);
            return;
        }

        if (method.equals("POST")) {
            Config config = ConfigUtil.getCONFIG();
            BeanUtil.copyProperties(gson.fromJson(req.getBody(), Config.class), config);
            String host = config.getHost();
            if (!ReUtil.contains("http(s*)://", host)) {
                host = "http://" + host;
            }
            config.setHost(host);
            ConfigUtil.sync();
            String json = gson.toJson(Result.success().setMessage("修改成功"));
            IoUtil.writeUtf8(res.getOut(), true, json);
        }
    }
}