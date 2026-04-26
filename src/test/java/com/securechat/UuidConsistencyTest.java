package com.securechat;

import com.securechat.entity.ChatRoom;
import com.securechat.entity.ChatRoomMember;
import com.securechat.entity.Message;
import com.securechat.entity.User;
import com.securechat.repository.ChatRoomMemberRepository;
import com.securechat.repository.ChatRoomRepository;
import com.securechat.repository.MessageRepository;
import com.securechat.repository.UserRepository;
import com.securechat.service.ChatRoomService;
import com.securechat.service.MessageService;
import com.securechat.service.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
public class UuidConsistencyTest {

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserSyncService userSyncService;

    @BeforeEach
    void setUp() {
        System.out.println("Setting up mock for passwordEncoder");
        // Mock passwordEncoder to return a fixed hash
        when(passwordEncoder.encode(anyString())).thenReturn("hashedPassword");
    }

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Test
    public void testUuidConsistencyFlow() {
        // 1. Define a specific UUID that would come from Keycloak
        UUID keycloakUuid = UUID.fromString("12345678-1234-5678-1234-567812345678");

        // 2. Mock a JWT token from Keycloak
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject(keycloakUuid.toString())
                .claim("preferred_username", "testuser")
                .claim("email", "testuser@example.com")
                .build();

        // 3. Sync user (Simulate login)
        User syncedUser = userSyncService.getOrCreateUser(jwt);

        // PROOF 1: The user ID in DB must match the Keycloak UUID exactly
        assertEquals(keycloakUuid, syncedUser.getId(), "User ID in DB must match Keycloak UUID");

        User dbUser = userRepository.findById(keycloakUuid).orElseThrow();
        assertEquals(keycloakUuid, dbUser.getId());

        // 4. Create a ChatRoom
        com.securechat.dto.ChatRoomCreateRequest createRequest = new com.securechat.dto.ChatRoomCreateRequest();
        createRequest.setName("Consistency Test Room");
        createRequest.setIsPrivate(false);
        createRequest.setMaxParticipants(10);

        ChatRoom room = chatRoomService.createChatRoom(createRequest, dbUser);

        // PROOF 2: The chatroom's created_by must be our Keycloak UUID
        assertEquals(keycloakUuid, room.getCreatedBy().getId());

        // PROOF 3: The creator must be added as a member with the same UUID
        boolean isMember = chatRoomMemberRepository.existsByChatRoomIdAndUserId(room.getId(), keycloakUuid);
        assertTrue(isMember, "User should be a member of the room they created");

        // 5. Send a Message
        Message message = messageService.createMessage(room.getId(), keycloakUuid, "Hello world!");

        // PROOF 4: The message sender ID must be our Keycloak UUID
        assertEquals(keycloakUuid, message.getSender().getId());

        // 6. Final verification of all relations in DB
        Message dbMessage = messageRepository.findById(message.getId()).orElseThrow();
        assertEquals(keycloakUuid, dbMessage.getSender().getId(), "Message sender ID in DB must be the Keycloak UUID");

        ChatRoomMember dbMember = chatRoomMemberRepository.findByChatRoomIdAndUserId(room.getId(), keycloakUuid)
                .orElseThrow();
        assertEquals(keycloakUuid, dbMember.getUser().getId(), "Member user ID in DB must be the Keycloak UUID");

        System.out.println(" UUID Consistency Verified: Keycloak(" + keycloakUuid + ") == DB_User(" + dbUser.getId()
                + ") == Message_Sender(" + dbMessage.getSender().getId() + ")");
    }
}
