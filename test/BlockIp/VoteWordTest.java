package BlockIp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoteWordTest {
    @Test
    void aVoteWordPaddedAsKotlinTrimsItIsStillOne() {
        assertTrue(BlockPlugin.isVoteWord(" Y "));
        assertTrue(BlockPlugin.isVoteWord("y "), "no-break space");
        assertTrue(BlockPlugin.isVoteWord(" yes　"), "em and ideographic space");
        assertTrue(BlockPlugin.isVoteWord("так "));
        assertFalse(BlockPlugin.isVoteWord("y y"));
        assertFalse(BlockPlugin.isVoteWord(" "));
        assertFalse(BlockPlugin.isVoteWord("yes!"));
    }
}
