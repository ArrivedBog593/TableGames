package com.github.arrivedbog593.tablegames.engine.table;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableAccessTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID GUEST = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static final boolean OPERATOR = true;
    private static final boolean ORDINARY = false;

    private static TableAccess owned() {
        TableAccess access = new TableAccess();
        access.claim(OWNER);
        return access;
    }

    @Test
    void theOwnerConfiguresTheirOwnTable() {
        TableAccess access = owned();
        assertTrue(access.mayConfigure(OWNER, ORDINARY));
        assertFalse(access.mayConfigure(STRANGER, ORDINARY));
    }

    @Test
    void guestsConfigureButDoNotShare() {
        // Sharing the sharing would be an escalation with no way back.
        TableAccess access = owned();
        access.trust(GUEST);

        assertTrue(access.mayConfigure(GUEST, ORDINARY));
        assertFalse(access.mayShare(GUEST, ORDINARY), "a guest must not add more guests");
        assertTrue(access.mayShare(OWNER, ORDINARY));
    }

    @Test
    void operatorsGetInEverywhere() {
        TableAccess access = owned();
        assertTrue(access.mayConfigure(STRANGER, OPERATOR));
        assertTrue(access.mayShare(STRANGER, OPERATOR));
    }

    @Test
    void anUnownedTableIsOperatorBusinessAndNotAnybodysToTake() {
        TableAccess access = new TableAccess();
        assertFalse(access.mayConfigure(STRANGER, ORDINARY));
        assertTrue(access.mayConfigure(STRANGER, OPERATOR));
        assertTrue(access.isUnclaimed());
    }

    @Test
    void claimingDropsTheGuestsThatCameWithIt() {
        // A table copied in creative carries the original's guests in its data.
        TableAccess access = owned();
        access.trust(GUEST);
        access.claim(STRANGER);

        assertTrue(access.trusted().isEmpty());
        assertFalse(access.mayConfigure(GUEST, ORDINARY));
        assertTrue(access.mayConfigure(STRANGER, ORDINARY));
    }

    @Test
    void trustingSomebodyTwiceChangesNothing() {
        TableAccess access = owned();
        assertTrue(access.trust(GUEST));
        assertFalse(access.trust(GUEST));
        assertEquals(1, access.trusted().size());
    }

    @Test
    void untrustingSomebodyWhoWasNeverThereReportsIt() {
        TableAccess access = owned();
        assertFalse(access.untrust(STRANGER));
        access.trust(GUEST);
        assertTrue(access.untrust(GUEST));
        assertFalse(access.mayConfigure(GUEST, ORDINARY));
    }

    @Test
    void theGuestListHasACeiling() {
        TableAccess access = owned();
        for (int i = 0; i < TableAccess.MAX_TRUSTED; i++) {
            assertTrue(access.trust(UUID.randomUUID()));
        }
        assertFalse(access.trust(GUEST), "a list written to disk must not grow without bound");
        assertEquals(TableAccess.MAX_TRUSTED, access.trusted().size());
    }

    @Test
    void aStoredListLongerThanTheCeilingIsTrimmedRatherThanRefused() {
        List<UUID> stored = new ArrayList<>();
        for (int i = 0; i < TableAccess.MAX_TRUSTED + 10; i++) {
            stored.add(UUID.randomUUID());
        }
        TableAccess access = new TableAccess();
        access.restore(OWNER, stored);

        assertEquals(TableAccess.MAX_TRUSTED, access.trusted().size());
        assertEquals(OWNER, access.owner().orElseThrow());
    }

    @Test
    void theGuestListCannotBeEditedFromOutside() {
        TableAccess access = owned();
        assertThrows(UnsupportedOperationException.class,
                () -> access.trusted().add(STRANGER));
    }
}
