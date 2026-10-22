package app.routes.telegram;

import app.core.FetchClient;

public class ScitechFansChannelRoute extends TelegramChannelRoute {
    public ScitechFansChannelRoute(FetchClient client) {
        super(client, "scitech_fans");
    }
}
