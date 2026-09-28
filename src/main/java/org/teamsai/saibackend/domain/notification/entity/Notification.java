package org.teamsai.saibackend.domain.notification.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.user.entity.User;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    private NotificationType notificationType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(name = "secondary_reference_id")
    private Long secondaryReferenceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public Notification(
            User user,
            NotificationType notificationType,
            String title,
            String content,
            Long referenceId,
            Long secondaryReferenceId
    ) {
        this.user = user;
        this.notificationType = notificationType;
        this.title = title;
        this.content = content;
        this.referenceId = referenceId;
        this.secondaryReferenceId = secondaryReferenceId;
    }

    public static Notification from(
            User user,
            NotificationType notificationType,
            String title,
            String content,
            Long referenceId,
            Long secondaryReferenceId
    ) {
        return Notification.builder()
                .user(user)
                .notificationType(notificationType)
                .title(title)
                .content(content)
                .referenceId(referenceId)
                .secondaryReferenceId(secondaryReferenceId)
                .build();
    }
}
