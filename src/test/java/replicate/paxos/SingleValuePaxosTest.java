package replicate.paxos;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import replicate.common.ClusterTest;
import replicate.common.MonotonicId;
import replicate.common.NetworkClient;
import replicate.common.TestUtils;
import replicate.net.InetAddressAndPort;
import replicate.paxos.messages.GetValueResponse;
import replicate.quorum.messages.GetValueRequest;
import replicate.quorum.messages.SetValueRequest;
import replicate.quorum.messages.SetValueResponse;
import replicate.wal.SetValueCommand;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class SingleValuePaxosTest extends ClusterTest<SingleValuePaxos> {
    SingleValuePaxos athens;
    SingleValuePaxos byzantium;
    SingleValuePaxos cyrene;

    @Before
    public void startCluster() throws IOException {
        super.nodes = TestUtils.startCluster(Arrays.asList("athens", "byzantium", "cyrene"),
                (name, config, clock, clientConnectionAddress, peerConnectionAddress, peerAddresses) -> {
                    return new SingleValuePaxos(name, clock, config, clientConnectionAddress, peerConnectionAddress, peerAddresses);
                });
        athens = nodes.get("athens");
        byzantium = nodes.get("byzantium");
        cyrene = nodes.get("cyrene");
    }

    @Test
    public void singleValuePaxosTest() throws IOException {
        var response = setValue(new SetValueRequest("title", "Microservices"), athens.getClientConnectionAddress());

        Assert.assertEquals("Microservices", response.result);
    }

    @Test
    public void singleValueNullPaxosGetTest() throws IOException {
        var client = new NetworkClient();
        var response = client.sendAndReceive(new GetValueRequest("title"), athens.getClientConnectionAddress(), GetValueResponse.class);
        assertEquals(Optional.empty(), response.value);
    }

    @Test
    public void AllNodesChooseOneValueEvenWithIncompleteWrites() throws IOException {
        //only athens has value Microservices
        //byzantium is empty, cyrene is empty
        athens.dropAfterNMessagesTo(byzantium, 1);
        athens.dropAfterNMessagesTo(cyrene, 1);
        //prepare succeeds on athens, byzantium and cyrene.
        //propose succeeds only on athens, as messages will be dropped to byzantium and cyrene
        var response = setValue(new SetValueRequest("title", "Microservices"), athens.getClientConnectionAddress());
        Assert.assertEquals("Error", response.result);

        assertEquals(athens.paxosState.promisedBallot(), new MonotonicId(2, 0)); //prepare from second attempt
        assertEquals(athens.paxosState.acceptedBallot(), Optional.of(new MonotonicId(1, 0)));
        SetValueCommand setValueCommand = athens.getAcceptedCommand();
        assertEquals(setValueCommand.getValue(), "Microservices");

        //only byzantium will have value Distributed Systems
        //athens has Microservices
        //cyrene is empty.
        byzantium.dropAfterNMessagesTo(cyrene, 1);
        response = setValue(new SetValueRequest("title", "Distributed Systems"), byzantium.getClientConnectionAddress());

        Assert.assertEquals("Error", response.result);
        assertEquals(byzantium.paxosState.promisedBallot(), new MonotonicId(2, 1)); //prepare from second attempt
        assertEquals(byzantium.paxosState.acceptedBallot(), Optional.of(new MonotonicId(1, 1)));

        setValueCommand = byzantium.getAcceptedCommand();

        assertEquals(setValueCommand.getValue(), "Distributed Systems");

        //only cyrene will have value "Event Driven Microservices" 1
        //athens has Microservices 2
        //byzantium has Distributed Systems. 3
        athens.reconnectTo(cyrene);
        byzantium.reconnectTo(cyrene);

        response = setValue(new SetValueRequest("title", "Event Driven Microservices"), cyrene.getClientConnectionAddress());

        Assert.assertEquals("Distributed Systems", response.result);
        assertEquals(cyrene.paxosState.promisedBallot(), new MonotonicId(2, 2)); //prepare from second attempt
        assertEquals(cyrene.paxosState.acceptedBallot(), Optional.of(new MonotonicId(2, 2)));

        assertEquals(cyrene.getAcceptedCommand().getValue(), "Distributed Systems");
        assertEquals(athens.getAcceptedCommand().getValue(), "Distributed Systems");
        assertEquals(byzantium.getAcceptedCommand().getValue(), "Distributed Systems");

        var getValueResponse = new NetworkClient().sendAndReceive(new GetValueRequest("title"), athens.getClientConnectionAddress(), GetValueResponse.class);
        assertEquals(Optional.of("Distributed Systems"), getValueResponse.value);


    }

    private SetValueResponse setValue(SetValueRequest request, InetAddressAndPort clientConnectionAddress) {
        try {
            NetworkClient client = new NetworkClient();
            return client.sendAndReceive(request, clientConnectionAddress, SetValueResponse.class);
        } catch (Exception e) {
            return new SetValueResponse("Error");
        }
    }

}