package BlockIp;

import arc.struct.ObjectIntMap;
import arc.util.Log;
import mindustry.gen.Player;

import java.lang.reflect.Field;

/**
 * A vanilla kick vote. Vanilla kicks the moment its counter reaches votesRequired(),
 * three at most, inside VoteSession.vote() - nothing runs between a vote and the kick. So BlockPlugin sets the
 * counter before each vote, from the votes kept back so far, and gives them back once the vote may pass.
 * The fields are package-private in v159.7 (NetServer$VoteSession: target, voted, votes); a build without
 * them leaves {@link #available} false, and BlockPlugin refuses such votes from players not trusted instead.
 */
final class KickVote {
    private KickVote() {
    }

    private static final Field TARGET, VOTED, VOTES;

    static {
        Field target = null, voted = null, votes = null;
        try {
            Class<?> session = Class.forName("mindustry.core.NetServer$VoteSession");
            target = session.getDeclaredField("target");
            voted = session.getDeclaredField("voted");
            votes = session.getDeclaredField("votes");
            target.setAccessible(true);
            voted.setAccessible(true);
            votes.setAccessible(true);
        } catch (Throwable e) {
            Log.warn("BlockIp: vanilla's vote session is not readable (@); a kick vote takes a trusted player to start", e.toString());
            target = voted = votes = null;
        }
        TARGET = target;
        VOTED = voted;
        VOTES = votes;
    }

    static boolean available() {
        return VOTES != null;
    }

    static Player target(Object session) throws IllegalAccessException {
        return (Player) TARGET.get(session);
    }

    @SuppressWarnings("unchecked")
    static ObjectIntMap<String> voted(Object session) throws IllegalAccessException {
        return (ObjectIntMap<String>) VOTED.get(session);
    }

    static int votes(Object session) throws IllegalAccessException {
        return VOTES.getInt(session);
    }

    static void setVotes(Object session, int votes) throws IllegalAccessException {
        VOTES.setInt(session, votes);
    }

    /** Yes votes a kick of a trusted player needs: {@code share} of the active players, never under three. */
    static int needed(int active, double share) {
        return Math.max(3, (int) Math.ceil(share * active));
    }

    /**
     * It may pass: a trusted player (or an admin) votes yes, and for a trusted target the yes votes reach
     * {@link #needed}; any other target's count is vanilla's own. Without a trusted yes, five bots farmed past
     * the vote minutes kicked any newcomer or mid-level regular on vanilla's three votes. While trust is new
     * ({@code newTrust}) nobody is trusted yet, so only a trusted (whitelisted) target's kick is held then.
     */
    static boolean mayPass(int yes, boolean trustedYes, boolean trustedTarget, boolean newTrust, int active, double share) {
        if (!trustedTarget) return trustedYes || newTrust;
        return trustedYes && yes >= needed(active, share);
    }

    /**
     * The counter to set before vanilla's vote(), which takes {@code lastVote} back and adds {@code sign}:
     * the whole count once it may pass, else at most what leaves 1 after the vote. 1, not votesRequired() - 1:
     * players leaving bring votesRequired() down to 2, and vanilla's own timer passes whatever reached it.
     */
    static int counter(int real, int lastVote, int sign, boolean mayPass) {
        return mayPass ? real : Math.min(real, 1 + lastVote - sign);
    }
}
