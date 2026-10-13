package BlockIp;

import mindustry.net.NetConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SameAddressTest {
    private static NetConnection connection(String address, boolean begun) {
        NetConnection con = new NetConnection(address) {
            @Override
            public void send(Object object, boolean reliable) {
            }

            @Override
            public void close() {
            }
        };
        con.hasBegunConnecting = begun;
        return con;
    }

    @Test
    void clientsStillLoadingTheMapCountTowardTheCap() {
        NetConnection loading = connection("1.1.1.1", true), joined = connection("1.1.1.1", true);
        joined.hasConnected = true;
        NetConnection kicked = connection("1.1.1.1", true), bare = connection("1.1.1.1", false), other = connection("2.2.2.2", true);
        kicked.kicked = true;
        NetConnection arriving = connection("1.1.1.1", false);
        List<NetConnection> open = List.of(loading, joined, kicked, bare, other, arriving);

        // Loading and joined count; one kicked, one that sent no connect packet yet, another address and itself do not
        assertEquals(2, BlockPlugin.sameAddress(open, arriving));
    }
}
