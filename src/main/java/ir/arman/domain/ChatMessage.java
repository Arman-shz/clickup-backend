package ir.arman.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A chat message. Serialised with the sender's name and avatar inline, exactly as the
 * SSE example in the spec shows.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage extends PanacheEntityBase {

    public static final String ID_PREFIX = "msg_";
    public static final String ID_SEQUENCE = "chat_messages_id_seq";

    @Id
    public String id;

    @Column(name = "sender_id", nullable = false)
    public String senderId;

    /**
     * Snapshotted for the same reason as WeeklyReport.userName, and because the SSE
     * broadcast then needs no per-message lookup to build the event.
     */
    @Column(name = "sender_name", nullable = false)
    public String senderName;

    @Column(name = "sender_avatar")
    public String senderAvatar;

    @Column(nullable = false)
    public String content;

    @Column(name = "file_url")
    public String fileUrl;

    /** Serialised as the spec's `timestamp`. The column avoids that SQL type keyword. */
    @Column(name = "sent_at", nullable = false)
    public Instant sentAt;
}
