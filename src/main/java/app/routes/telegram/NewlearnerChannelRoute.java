package app.routes.telegram;

import app.core.FetchClient;

public class NewlearnerChannelRoute extends TelegramChannelRoute {
    public NewlearnerChannelRoute(FetchClient client) {
        super(client, "NewlearnerChannel");
    }
}
