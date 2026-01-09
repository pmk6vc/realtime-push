package messaging;

import java.util.UUID;

public record IncomingMessage(UUID channelId, String text) {}
