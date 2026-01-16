package messaging.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.UUID;
import messaging.persistence.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for message serialization with special characters. */
class MessageSerializationTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
  }

  @Test
  void ackMessage_serializesCorrectly() throws JsonProcessingException {
    AckMessage ack = AckMessage.create("user-123", "session-456");
    String json = objectMapper.writeValueAsString(ack);

    assertTrue(json.contains("\"type\":\"ack\""));
    assertTrue(json.contains("\"userId\":\"user-123\""));
    assertTrue(json.contains("\"sessionId\":\"session-456\""));
  }

  @Test
  void errorMessage_serializesCorrectly() throws JsonProcessingException {
    ErrorMessage error = ErrorMessage.create("Something went wrong");
    String json = objectMapper.writeValueAsString(error);

    assertTrue(json.contains("\"type\":\"error\""));
    assertTrue(json.contains("\"message\":\"Something went wrong\""));
  }

  @Test
  void errorMessage_escapesQuotesInMessage() throws JsonProcessingException {
    ErrorMessage error = ErrorMessage.create("Invalid \"field\" value");
    String json = objectMapper.writeValueAsString(error);

    assertTrue(json.contains("\\\"field\\\""), "Quotes should be escaped: " + json);
  }

  @Test
  void errorMessage_escapesNewlinesInMessage() throws JsonProcessingException {
    ErrorMessage error = ErrorMessage.create("Line 1\nLine 2\rLine 3");
    String json = objectMapper.writeValueAsString(error);

    assertTrue(json.contains("\\n"), "Newlines should be escaped: " + json);
    assertTrue(json.contains("\\r"), "Carriage returns should be escaped: " + json);
  }

  @Test
  void errorMessage_escapesBackslashesInMessage() throws JsonProcessingException {
    ErrorMessage error = ErrorMessage.create("Path: C:\\Users\\test");
    String json = objectMapper.writeValueAsString(error);

    assertTrue(json.contains("\\\\"), "Backslashes should be escaped: " + json);
  }

  @Test
  void errorMessage_escapesTabsInMessage() throws JsonProcessingException {
    ErrorMessage error = ErrorMessage.create("Column1\tColumn2");
    String json = objectMapper.writeValueAsString(error);

    assertTrue(json.contains("\\t"), "Tabs should be escaped: " + json);
  }

  @Test
  void errorMessage_escapesUnicodeControlCharacters() throws JsonProcessingException {
    // Bell character (ASCII 7) and form feed (ASCII 12)
    ErrorMessage error = ErrorMessage.create("Text with \u0007bell and \u000Cform feed");
    String json = objectMapper.writeValueAsString(error);

    // Jackson escapes control characters as Unicode escape sequences
    assertTrue(
        json.contains("\\u0007") || !json.contains("\u0007"),
        "Bell character should be escaped: " + json);
  }

  @Test
  void broadcastMessage_serializesCorrectly() throws JsonProcessingException {
    UUID channelId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    UUID senderId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    Message message = Message.create(channelId, senderId, "Hello world!");
    BroadcastMessage broadcast = BroadcastMessage.fromMessage(message);
    String json = objectMapper.writeValueAsString(broadcast);

    assertTrue(json.contains("\"type\":\"message\""));
    assertTrue(json.contains("\"messageId\":\""));
    assertTrue(json.contains("\"channelId\":\"22222222-2222-2222-2222-222222222222\""));
    assertTrue(json.contains("\"from\":\"33333333-3333-3333-3333-333333333333\""));
    assertTrue(json.contains("\"text\":\"Hello world!\""));
  }

  @Test
  void broadcastMessage_escapesSpecialCharactersInText() throws JsonProcessingException {
    UUID channelId = UUID.randomUUID();
    UUID senderId = UUID.randomUUID();

    String textWithSpecialChars = "Hello \"world\"!\nNew line\tTab\\Backslash";
    Message message = Message.create(channelId, senderId, textWithSpecialChars);
    BroadcastMessage broadcast = BroadcastMessage.fromMessage(message);
    String json = objectMapper.writeValueAsString(broadcast);

    assertTrue(json.contains("\\\"world\\\""), "Quotes should be escaped: " + json);
    assertTrue(json.contains("\\n"), "Newlines should be escaped: " + json);
    assertTrue(json.contains("\\t"), "Tabs should be escaped: " + json);
    assertTrue(json.contains("\\\\"), "Backslashes should be escaped: " + json);
  }

  @Test
  void broadcastMessage_handlesEmoji() throws JsonProcessingException {
    UUID channelId = UUID.randomUUID();
    UUID senderId = UUID.randomUUID();

    String textWithEmoji = "Hello 👋 World 🌍!";
    Message message = Message.create(channelId, senderId, textWithEmoji);
    BroadcastMessage broadcast = BroadcastMessage.fromMessage(message);
    String json = objectMapper.writeValueAsString(broadcast);

    // Verify the JSON can be parsed back and contains the emoji
    BroadcastMessage parsed = objectMapper.readValue(json, BroadcastMessage.class);
    assertEquals(textWithEmoji, parsed.text());
  }

  @Test
  void errorMessage_canBeDeserializedAfterSerialization() throws JsonProcessingException {
    ErrorMessage original = ErrorMessage.create("Test \"message\" with\nspecial chars");
    String json = objectMapper.writeValueAsString(original);
    ErrorMessage parsed = objectMapper.readValue(json, ErrorMessage.class);

    assertEquals(original.type(), parsed.type());
    assertEquals(original.message(), parsed.message());
  }

  @Test
  void ackMessage_canBeDeserializedAfterSerialization() throws JsonProcessingException {
    AckMessage original = AckMessage.create("user-123", "session-456");
    String json = objectMapper.writeValueAsString(original);
    AckMessage parsed = objectMapper.readValue(json, AckMessage.class);

    assertEquals(original.type(), parsed.type());
    assertEquals(original.userId(), parsed.userId());
    assertEquals(original.sessionId(), parsed.sessionId());
  }
}
