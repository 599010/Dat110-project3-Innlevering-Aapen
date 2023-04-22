/**
 * 
 */
package no.hvl.dat110.middleware;

import java.math.BigInteger;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import no.hvl.dat110.rpc.interfaces.NodeInterface;
import no.hvl.dat110.util.LamportClock;
import no.hvl.dat110.util.Util;

/**
 * @author tdoy
 *
 */
public class MutualExclusion {
		
	private static final Logger logger = LogManager.getLogger(MutualExclusion.class);
	/** lock variables */
	private boolean CS_BUSY = false;						// indicate to be in critical section (accessing a shared resource) 
	private boolean WANTS_TO_ENTER_CS = false;				// indicate to want to enter CS
	private List<Message> queueack; 						// queue for acknowledged messages
	private List<Message> mutexqueue;						// queue for storing process that are denied permission. We really don't need this for quorum-protocol
	
	private LamportClock clock;								// lamport clock
	private Node node;
	
	public MutualExclusion(Node node) throws RemoteException {
		this.node = node;
		
		clock = new LamportClock();
		queueack = new ArrayList<Message>();
		mutexqueue = new ArrayList<Message>();
	}
	
	public synchronized void acquireLock() {
		CS_BUSY = true;
	}
	
	public void releaseLocks() {
		WANTS_TO_ENTER_CS = false;
		CS_BUSY = false;
	}

	public boolean doMutexRequest(Message message, byte[] updates) throws RemoteException {
		
	    logger.info(node.nodename + " wants to access CS");
	    
	    // clear the queueack before requesting for votes
	    queueack.clear();
	    
	    // clear the mutexqueue
	    mutexqueue.clear();
	    
	    // increment clock
	    clock.increment();
	    
	    // adjust the clock on the message, by calling the setClock on the message
	    message.setClock(message.getClock());
	    
	    // wants to access resource - set the appropriate lock variable
	    setLock(true);
	    
	    // start MutualExclusion algorithm
	    // first, call removeDuplicatePeersBeforeVoting. A peer can hold/contain 2 replicas of a file. This peer will appear twice
	    List<Message> activenodes = removeDuplicatePeersBeforeVoting();
	    
	    // multicast the message to activenodes (hint: use multicastMessage)
	    multicastMessage(message, activenodes);
	    
	    // check that all replicas have replied (permission)
	    boolean permission = areAllMessagesReturned(activenodes.size());
	    
	    // if yes, acquireLock
	    if (permission) {
	        acquireLock();
	        node.broadcastUpdatetoPeers(updates);
	        clearMutexQueue();
	    }
	    
	    // clear the queueack
	    queueack.clear();
	    
	    // return permission
	    return permission;
	}
	
	// multicast message to other processes including self
	private void multicastMessage(Message message, List<Message> activenodes) throws RemoteException {
			
	    logger.info("Number of peers to vote = " + activenodes.size());
		
	    // iterate over the activenodes
	    for (Message node : activenodes) {
	        // obtain a stub for each node from the registry
	        NodeInterface nodeStub = Util.getProcessStub(node.getNodeName(), node.getPort());
	        // call onMutexRequestReceived()
	        nodeStub.onMutexRequestReceived(message);
	    }
	}
	
	public void onMutexRequestReceived(Message message) throws RemoteException {
		
	    // increment the local clock
	    clock.increment();
			
	    // if message is from self, acknowledge, and call onMutexAcknowledgementReceived()
	    if (message.getNodeID().equals(node.getNodeID()) && message.getPort() == node.getPort()) {
	        logger.info(node.nodename + ": received own message");
	        queueack.add(message);
	        onMutexAcknowledgementReceived(message);
	    } else {
	        int caseid = -1;

	        // write if statement to transition to the correct caseid
	        if (!isLocked() && !mutexqueue.contains(message)) {
	            caseid = 0;
	        } else if (isLocked()) {
	            caseid = 1;
	        } else if (!isLower(message, node.getNodeID())) {
	            mutexqueue.add(message);
	            caseid = 2;
	        } else {
	            caseid = 0;
	            Message reply = new Message(node.getNodeID(), node.getNodeName(), message.getPort());
	            NodeInterface senderStub = Util.getProcessStub(message.getNodeName(), message.getPort());
	            senderStub.onMutexAcknowledgementReceived(reply);
	        }
			
	        // check for decision
	        doDecisionAlgorithm(message, mutexqueue, caseid);
	    }
	}
	
	public void doDecisionAlgorithm(Message message, List<Message> queue, int condition) throws RemoteException {

	    String procName = message.getNodeName();
	    int port = message.getPort();

	    switch(condition) {

	        /** case 1: Receiver is not accessing shared resource and does not want to (send OK to sender) */
	        case 0: {

	            // get a stub for the sender from the registry
	            NodeInterface sender = Util.getProcessStub(procName, port);

	            // acknowledge message
	            System.out.println(procName + ": Received from " + message.getNodeName() + ", condition is " + condition);

	            // send acknowledgement back by calling onMutexAcknowledgementReceived()
	            sender.onMutexAcknowledgementReceived(message);

	            break;
	        }

	        /** case 2: Receiver already has access to the resource (dont reply but queue the request) */
	        case 1: {

	            // queue this message
	            queue.add(message);
	            System.out.println(procName + ": Queued " + message.getNodeName() + ", condition is " + condition);

	            break;
	        }

	        /**
	         *  case 3: Receiver wants to access resource but is yet to (compare own message clock to received message's clock
	         *  the message with lower timestamp wins) - send OK if received is lower. Queue message if received is higher
	         */
	        case 2: {

	            // check the clock of the sending process (note that the correct clock is in the message)
	            int senderClock = message.getClock();

	            // own clock for the multicast message (note that the correct clock is in the message)
	            int ownClock = message.getClock();

	            // compare clocks, the lowest wins
	            if (senderClock < ownClock) {

	                // if sender wins, acknowledge the message, obtain a stub and call onMutexAcknowledgementReceived()
	                NodeInterface sender = Util.getProcessStub(procName, port);
	                System.out.println(procName + ": Received from " + message.getNodeName() + ", condition is " + condition + ", ownClock is " + ownClock + ", senderClock is " + senderClock);
	                sender.onMutexAcknowledgementReceived(message);

	            } else if (senderClock == ownClock) {

	                // if clocks are the same, compare nodeIDs, the lowest wins
	                if (procName.compareTo(message.getNodeName()) > 0) {

	                    // if sender wins, acknowledge the message, obtain a stub and call onMutexAcknowledgementReceived()
	                    NodeInterface sender = Util.getProcessStub(procName, port);
	                    System.out.println(procName + ": Received from " + message.getNodeName() + ", condition is " + condition + ", ownClock is " + ownClock + ", senderClock is " + senderClock);
	                    sender.onMutexAcknowledgementReceived(message);

	                } else {

	                    // if sender looses, queue it
	                    queue.add(message);
	                    System.out.println(procName + ": Queued " + message.getNodeName() + ", condition is " + condition + ", ownClock is " + ownClock + ", senderClock is " + senderClock);

	                }

	            } else {

	                // if sender looses, queue it
	                queue.add(message);
	                System.out.println(procName + ": Queued " + message.getNodeName() + ", condition is " + condition + ", ownClock is " + ownClock + ", senderClock is " + senderClock);

	            }

	            break;
	        }

	        default: break;
	    }

	}

	public void onMutexAcknowledgementReceived(Message message) throws RemoteException {

	    // add message to queueack
	    queueack.add(message);
	    System.out.println(message.getNodeName() + ": Acknowledged");

	}

	
	// multicast release locks message to other processes including self
	public void multicastReleaseLocks(Set<Message> activenodes) {
	    logger.info("Releasing locks from = " + activenodes.size());

	    // iterate over the activenodes
	    for (Message msg : activenodes) {
	        try {
	            // obtain a stub for each node from the registry
	            NodeInterface node = Util.getProcessStub(msg.getNodeName(), msg.getPort());

	            // call releaseLocks()
	            node.releaseLocks();
	        } catch (RemoteException e) {
	            e.printStackTrace();
	        }
	    }
	}

	private boolean areAllMessagesReturned(int numvoters) throws RemoteException {
	    logger.info(node.getNodeName() + ": size of queueack = " + queueack.size());

	    // check if the size of the queueack is same as the numvoters
	    if (queueack.size() == numvoters) {
	        // clear the queueack
	        queueack.clear();
	        // return true if yes and false if no
	        return true;
	    } else {
	        return false;
	    }
	}

	
	private List<Message> removeDuplicatePeersBeforeVoting() {
		
		List<Message> uniquepeer = new ArrayList<Message>();
		for(Message p : node.activenodesforfile) {
			boolean found = false;
			for(Message p1 : uniquepeer) {
				if(p.getNodeName().equals(p1.getNodeName())) {
					found = true;
					break;
				}
			}
			if(!found)
				uniquepeer.add(p);
		}		
		return uniquepeer;
	}
}
