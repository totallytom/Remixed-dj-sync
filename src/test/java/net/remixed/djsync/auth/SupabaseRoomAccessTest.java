package net.remixed.djsync.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupabaseRoomAccessTest {

    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final String B = "22222222-2222-2222-2222-222222222222";
    private static final String C = "33333333-3333-3333-3333-333333333333";

    // Unreachable URL: these cases must be decided without any network call.
    private final SupabaseRoomAccess access =
            new SupabaseRoomAccess("http://127.0.0.1:9", "key", System::currentTimeMillis);

    @Test
    void dmRoomIsOpenToItsTwoMembersOnly() {
        String room = A + "_" + B;
        assertThat(access.canJoin(A, room)).isTrue();
        assertThat(access.canJoin(B, room)).isTrue();
        assertThat(access.canJoin(C, room)).isFalse();
    }

    @Test
    void unsortedDmIdIsNotARealConversation() {
        assertThat(access.canJoin(A, B + "_" + A)).isFalse();
    }

    @Test
    void junkRoomIdsAreRejected() {
        assertThat(access.canJoin(A, "../../etc")).isFalse();
        assertThat(access.canJoin(A, "")).isFalse();
    }

    @Test
    void groupCheckFailsClosedWhenSupabaseIsUnreachable() {
        assertThat(access.canJoin(A, C)).isFalse();
    }
}
