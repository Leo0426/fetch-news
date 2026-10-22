package app.routes.telegram;

import app.core.FetchClient;

public class SolidotChannelRoute extends TelegramChannelRoute {
    public SolidotChannelRoute(FetchClient client) {
        super(client, "solidot");
    }
}
