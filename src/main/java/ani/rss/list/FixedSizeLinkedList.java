package ani.rss.list;

import ani.rss.entity.Config;
import ani.rss.util.ConfigUtil;

import java.util.LinkedList;

public class FixedSizeLinkedList<T> extends LinkedList<T> {

    public FixedSizeLinkedList() {
        super();
    }

    @Override
    public boolean add(T t) {
        boolean r = super.add(t);
        Config config = ConfigUtil.CONFIG;
        int logsMax = config.getLogsMax();
        if (size() > logsMax) {
            removeRange(0, size() - logsMax);
        }
        return r;
    }
}
