package wifi;
import java.util.concurrent.ArrayBlockingQueue;
import rf.RF;
import java.util.Arrays;
import java.util.Random;
import java.util.Map;
import java.util.HashMap;
import java.io.PrintWriter;
/**
 * This class is going to be used as a thread to send packets.This thread deals with everything sending packet wise. The only sending that it does not do is the acks
 * The acks are dealt with in the reciever because it is easier to send ack packets then. The thread takes in a send data array blockign queue that blocks until we have data to send and then takes the packets
 * out one by one in order to send them in the correct order and to make sure that the correct process is being implemented when we are sending the data. We chose to do the sending in 
 * terms of switches. We have an inuse state, a no user state, and interrupted state, a wait ack state and a send beacon state. Each state has its own specifications and deals 
 * with the miscellaneous waiting and calculations when we are sending a packet. It also takes in commans in which weprint out where we are at in debug mode and allow the user to add stuff
 * depending on what they want the specifications to be (IE how long they want the wait to be inbetween the beacons). 
 *
 * @Gennie Cheatham, Ricardo Barraza, Braden Ash
 * @November 7, 2019
 */
public class StreamSender implements Runnable
{
    public enum State{INUSE, NOUSE, INTERRUPTED, WAITACK, SENDBEACON};
    private ArrayBlockingQueue<Packets> sendData;//the data that is waiting to be sent 
    private ArrayBlockingQueue<Packets> ackReceived;//the acks that we have received to make sure that the data got through
    private RF theRF;//the rRF layer
    private State myState; // The state we're in
    private short ourMAC;//our MAC address
    private PrintWriter output; // The output stream we'll write to
    private Map<Short, Integer> seqSend;//the map that holds the seqence numbers of the ones we are sending to 
    private int[] commands;//the command values that the user has initialized
    private int[] status;//the status which keeps track of the errors and things that happen in the send sequences
    private long[] extraTime;//our extra time to add to the RF clock to make sure that our clocks will synchronize

    /**
     * Constructor for the thread of class StreamSender.
     */
    public StreamSender(ArrayBlockingQueue<Packets> sendData, ArrayBlockingQueue<Packets> ackReceived, Map<Short, Integer> seqSend, short ourMAC, RF theRF,PrintWriter output, int[] commands,int[] status,long extraTime[])
    {
        this.sendData = sendData; 
        this.ackReceived = ackReceived;
        this.seqSend = seqSend;
        this.ourMAC = ourMAC;
        this.theRF = theRF;
        this.output = output; 
        this.commands = commands;
        this.status=status;
        this.extraTime=extraTime;
    }

    /**
     * Method continually runs to take packets off the ArrayBlocking Queue and transmit the packets out. It implements the runnable interface in order to make everything thread safe.
     */
    public void run()
    {
        //constants for DIFS time
        final long DIFS = theRF.aSIFSTime+2*theRF.aSlotTime;

        //variables to track how much time has been waited
        long difswaited = 0;
        long randTime = 0;
        long randTimeWaited = 0;

        //random generator to generate the random wait time
        Random random = new Random();
        boolean randomWait = false;

        //sets a default state
        myState = State.NOUSE;
        //sets a default window size
        int windowSize = theRF.aCWmin+1;
        //initialize retransmit time and timeout
        int retransmitTimes = 0;
        long timeout = 0;
        long nextBeacon = 0;
        boolean beaconDisabled = false;

        //probably dont need
        if(commands[1] == 0){
            this.commands[1] = 0;
        }

        for(;;){
            try{
                switch(myState){
                    //the channel is in use and therefore you cannot send the packet
                    case INUSE:
                    //if channel is idle move to NOUSE state
                    if(commands[3]>0){
                        beaconDisabled=false;
                    } 
                    if(theRF.inUse()==false && (((theRF.clock() + extraTime[0])<nextBeacon)||beaconDisabled)){
                        myState = State.NOUSE;
                        //if channel is busy stay in INUSE
                    }else if(theRF.inUse() == true){ 
                        myState = State.INUSE;
                    }else if(theRF.inUse() == false && (((theRF.clock() + extraTime[0]) >= nextBeacon) &&beaconDisabled==false)){
                        myState = State.SENDBEACON;
                    }
                    break;

                    //start waiting IFS and random wait
                    //the channel is not in use and you can send packets
                    case NOUSE:
                    //if medium is idle and we have something to send
                    if(commands[3]>0){
                        beaconDisabled=false;
                    } 
                    if(theRF.inUse()==false && sendData.size()>0 && (((theRF.clock() + extraTime[0]) < nextBeacon)||beaconDisabled)){
                        // Debug outprint based on retry attempts
                        if(commands[1] == -1&&retransmitTimes==0){
                            output.println("Starting collision window at [0..."+(windowSize-1)+"]");
                        }
                        else if(commands[1] == -1){
                            output.println("Doubled collision window -- is now [0..."+(windowSize-1)+"]");
                        }
                        //start tracking DIFS
                        long startDifs = (theRF.clock() + extraTime[0]);
                        // Debug outprint based on number of retry attempts
                        if(commands[1] == -1&&retransmitTimes==0){
                            output.println("Moving to IDLE_DIFS_WAIT with pending DATA");
                        } 
                        else if(commands[1]==-1){
                            output.println("Moving to BUSY_WAIT after ACK timeout.");
                            output.println("Waiting for DIFS to elapse after currentTx...");
                        }
                        // If havent waited long enough for DIFS, put thread to sleep
                        while(theRF.inUse() == false && difswaited<DIFS){
                            Thread.sleep(DIFS - difswaited);
                            long sleeping = (theRF.clock() + extraTime[0]);
                            long finalSleep = roundTime(sleeping);
                            Thread.sleep(finalSleep - (theRF.clock() + extraTime[0]));
                            break;
                        }
                        long endDifs = (theRF.clock() + extraTime[0]);
                        //determine DIFS waited
                        difswaited += endDifs - startDifs; 
                        //Debug statement
                        if(commands[1] == -1){
                            output.println("IDLE waited until " + (theRF.clock()+extraTime[0]));
                        } 
                        //waited DIFS and the medium is still idle
                        if(difswaited >= DIFS && theRF.inUse() == false){
                            //once we have waited the full amount of difs, we reset difs waited 
                            difswaited = 0;
                            //you want to increment if it is a retransmission
                            if(randomWait==true){
                                //If slot interval was set by user, use that input for time intervals
                                if(commands[2] == 0){
                                    //window cannot be greater than max size
                                    //choosing a random slot to wait 
                                    randTime = random.nextInt(windowSize) * theRF.aSlotTime;
                                    System.out.println("RandTime"+randTime);
                                    //start tracking random wait time
                                    long startRand = (theRF.clock() + extraTime[0]);

                                    //wait the random wait time
                                    while(theRF.inUse() == false && randTimeWaited<randTime){
                                        Thread.sleep(randTime);
                                        long sleeping = (theRF.clock() + extraTime[0]);
                                        long finalSleep = roundTime(sleeping);
                                        Thread.sleep(finalSleep - (theRF.clock() + extraTime[0]));
                                        break;
                                    }
                                    long randEnd = (theRF.clock() + extraTime[0]);
                                    randTimeWaited += randEnd - startRand;
                                    if(commands[1] == -1){
                                        output.println("IDLE waited until " + (theRF.clock()+extraTime[0]));
                                    } 

                                    //waited random wait time and channel is idle
                                    if(randTimeWaited > randTime && theRF.inUse() == false){
                                        Packets packet = sendData.peek();
                                        if(packet==null){
                                            status[0]=7;
                                        }
                                        byte[] data = packet.toByte();
                                        
                                        theRF.transmit(data); 
                                        if(commands[1] == -1){
                                            output.println("Transmitting DATA after DIFS+SLOTs wait at " + (theRF.clock()+extraTime[0]));
                                        } 
                                        
                                        timeout = (theRF.clock() + extraTime[0]) + (theRF.aSIFSTime * 2);
                                        if(packet.getDestAdd()==-1){
                                            sendData.take();
                                            myState = State.NOUSE;
                                        }
                                        else{
                                            myState = State.WAITACK;
                                        }
                                    }else{
                                        //if you did not wait the full random wait time then you were interrupted
                                        myState = State.INTERRUPTED;
                                    }
                                }
                                // If there was no user command for slot intervals use random intervals
                                else if(commands[2] != 0){
                                    //window cannot be greater than max size
                                    //choosing a random slot to wait 
                                    randTime = (windowSize-1)*theRF.aSlotTime;
                                    System.out.println("Max randTime"+randTime);
                                    //start tracking random wait time
                                    long startRand = (theRF.clock() + extraTime[0]);

                                    //wait the random wait time
                                    while(theRF.inUse() == false && randTimeWaited<randTime){
                                        Thread.sleep(randTime);
                                        long sleeping = (theRF.clock() + extraTime[0]);
                                        long finalSleep = roundTime(sleeping);
                                        Thread.sleep(finalSleep - (theRF.clock() + extraTime[0]));
                                        break;
                                    }
                                    long randEnd = (theRF.clock() + extraTime[0]);
                                    randTimeWaited += randEnd - startRand;
                                    
                                    //waited random wait time and channel is idle
                                    if(randTimeWaited > randTime && theRF.inUse() == false){
                                        Packets packet = sendData.peek();
                                        if(packet==null){
                                            status[0]=7;
                                        }
                                        byte[] data = packet.toByte();
                                        
                                        theRF.transmit(data); 
                                        if(commands[1] == -1){
                                            output.println("Transmitting DATA after DIFS+SLOTs wait at " + (theRF.clock()+extraTime[0]));
                                        } 
                                        timeout = (theRF.clock() + extraTime[0]) + (theRF.aSIFSTime * 2);
                                        if(packet.getDestAdd()==-1){
                                            sendData.take();
                                            if(packet==null){
                                                status[0]=7;
                                            }
                                            myState = State.NOUSE;
                                        }
                                        else{
                                            if(commands[1] == -1){
                                                output.println("Moving to AWAIT_ACK after sending DATA ");
                                            } 
                                            myState = State.WAITACK;
                                        }
                                    }else{
                                        //if you did not wait the full random wait time then you were interrupted
                                        if(commands[1] == -1){
                                            output.println("IDLE waited until " + (theRF.clock()+extraTime[0]));
                                        } 
                                        myState = State.INTERRUPTED;
                                    }
                                }
                            }
                            //No random wait time
                            else if(randomWait==false){
                                //when to transmit if there is no random wait time
                                Packets packet = sendData.peek();
                                if(packet==null){
                                    status[0]=7;
                                }
                                byte[] data = packet.toByte();
                                theRF.transmit(data);
                                timeout = (theRF.clock() + extraTime[0]) + (theRF.aSIFSTime * 2);
                                if(commands[1] == -1){
                                    output.println("Transmitting DATA after a simple DIFS wait at "+(theRF.clock()+extraTime[0]));
                                }
                                //transmission will be a broadcast
                                if(packet.getDestAdd()==-1){
                                    sendData.take();
                                    if(packet==null){
                                        status[0]=7;
                                    }
                                    if(commands[1] == -1){
                                        output.println(" " + difswaited);
                                    } 
                                    myState = State.NOUSE;
                                }
                                //Need to receive an ack since destination is actual host
                                else{
                                    if(commands[1] == -1){
                                        output.println("Moving to AWAIT_ACK after sending data");
                                    } 
                                    myState = State.WAITACK;
                                }
                            }
                        }else {
                            //if we didn't wait for the full amount of difs or the rf is in use then we know we were interrupted
                            myState = State.INTERRUPTED;
                        }
                    }
                    //if the RF is in use but we don't have anything to send
                    else if(theRF.inUse() == false && sendData.size() == 0 && (((theRF.clock() + extraTime[0])<nextBeacon)||beaconDisabled)){
                        //we have nothing to send 
                        myState = State.NOUSE;
                    }
                    //prepare to send beacon
                    else if(((theRF.clock() + extraTime[0])>= nextBeacon)||!beaconDisabled){
                        if(commands[1] == -1 && commands[3] >= 0){
                            output.println("Sending Beacon");
                        }

                        myState = State.SENDBEACON;
                    }
                    //RF is in use and cannot transmit
                    else{
                        //if the rf is in use then we move to our state INUSE
                        myState = State.INUSE;
                    }
                    break;
                    case INTERRUPTED:
                    //sending was interrupted, break and go to a different case
                    randomWait=true;//since it was interrupted we now want to do random waiting and increase the window 
                    if(theRF.inUse() == true){
                        myState = State.INUSE;
                    }else{
                        myState = State.NOUSE;
                    }
                    break;
                    // Case for waiting to receive an ACK
                    case WAITACK:
                    Thread.sleep(5);
                    if(commands[3]>0){
                        beaconDisabled=false;
                    } 
                    // A timeout has occured
                    if((theRF.clock() + extraTime[0]) >= timeout){
                        //if the packet has tried to retransmit the max amount of times
                        if(commands[1] == -1){
                            output.println("Ack timer expired at " + (theRF.clock()+extraTime[0]));
                        } 
                        if(retransmitTimes >= theRF.dot11RetryLimit){
                            Packets packet =sendData.take();
                            if(packet==null){
                                status[0]=7;
                            }
                            // Debug outprint
                            if(commands[1] == -1){
                                output.println("Moving to AWAIT_PACKET after exceeding retry limit ");
                             }
                            output.println("Tried to retransmit too many times");
                            retransmitTimes = 0;
                            status[0]=5;
                            windowSize=theRF.aCWmin+1;
                            myState = State.NOUSE;
                            break;
                        }
                        //peek at the top of the sendData queue
                        Packets packetOn = sendData.peek();
                        if(packetOn==null){
                            status[0]=7;
                        }
                        byte[] data = packetOn.toByte();
                        //change retransmit bit
                        packetOn.setRetransmission((byte)1);
                        packetOn.setControl(packetOn.getFrameType(),packetOn.getRetransmission(),packetOn.getSequenceNum());
                        retransmitTimes = retransmitTimes+1;
                        randomWait=true;
                        if(commands[1] == -1){
                            output.println("Retransmitting");
                        }
                        //this is where we want to double the window
                        if(windowSize <= (theRF.aCWmax+1)){ 
                            windowSize = ((2*(windowSize)));
                            if(windowSize >= (theRF.aCWmax+1)){
                                windowSize = theRF.aCWmax+1;
                            }
                        }
                        else{
                            //We will never reach here
                            windowSize=theRF.aCWmax+1;
                        }
                        myState = State.NOUSE;
                    }
                    if(ackReceived.size()>0){
                        //remove data from the queue if the ack has the right sequence number 
                        Packets ackPack = ackReceived.take();
                        if(ackPack==null){
                            status[0]=7;
                        }
                        Packets sendPack = sendData.peek();
                        if(sendPack==null){
                            status[0]=7;
                        }
                        // correct sequence number so packet is in correct order
                        if(ackPack.getSequenceNum() == sendPack.getSequenceNum()){
                            sendData.take();
                            retransmitTimes = 0;
                            windowSize = theRF.aCWmin;
                            status[0]=4;
                            myState = State.NOUSE;
                        }
                        //packet recieved out of order
                        else{
                            output.println("Received out of order");
                            sendData.take();
                            status[0]=2;
                            myState = State.NOUSE;
                        }
                    }
                    break;
                    //what is an appropriate interval for beacon frames
                    //Prepring to sen beacon 
                    case SENDBEACON:
                    
                    //Beacons have been disabled so no need to transmit
                    if(commands[3] == -1 && beaconDisabled == false){
                        output.println("Beacons have been disabled.");  
                        beaconDisabled=true;
                        myState = State.NOUSE;
                        break;
                    }
                    //Beacons were already disabled so only need to change state
                    else if(beaconDisabled == true && commands[3] == -1){
                        myState= State.NOUSE;
                        break;
                    }
                    //Beacons have not been disabled and will be transmitted, user inputted a desired interval for beacon transmission which is used
                    else if(commands[3] > 0){ 
                        beaconDisabled=false;
                        //build beacon packet
                        Packets beacon = new Packets(ourMAC);
                        beacon.setFrameType((byte)2);
                        beacon.setSourceAdd(ourMAC);
                        beacon.setDestAdd((short)-1);
                        beacon.setSequenceNum((short) 0);
                        beacon.setControl(beacon.getFrameType(),(byte)0,beacon.getSequenceNum());
                        //set the data to what we want for the BEACON
                        //In our tests that we ran, creating and sending a beacon took 1804.2 ms so we rounded to 1805
                        long beaconData = (theRF.clock() + extraTime[0]) + 1805;
                        byte[] beaconArray = new byte[8];

                        //bitwise operations on data in beacon array
                        for(int i=beaconArray.length-1; i>=0; i--){
                            beaconArray[i] = (byte) beaconData;
                            beaconData >>>= 8;
                        }
                        beacon.setData(beaconArray);
                        byte[] beaconByte = beacon.toByte();
                        theRF.transmit(beaconByte);
                        //here is where we set our regular info
                        nextBeacon = (theRF.clock() + extraTime[0]) + (commands[3]*1000);
                        myState=State.NOUSE;
                        break;
                    }
                    //Beacons have not been disabled and no desired transmission interval set, use 3 seconds
                    else{
                        //send a beacon every 105ms
                        beaconDisabled=false;
                        Packets beacon = new Packets(ourMAC);
                        beacon.setFrameType((byte)2);
                        beacon.setSourceAdd(ourMAC);
                        beacon.setDestAdd((short)-1);
                        beacon.setSequenceNum((short) 0);
                        beacon.setControl(beacon.getFrameType(),(byte)0,beacon.getSequenceNum());
                        //set the data to what we want for the BEACON
                        //In our tests that we ran, creating and sending a beacon took 1804.2 ms so we rounded to 1805
                        long beaconData = (theRF.clock() + extraTime[0]) + 1805;
                        byte[] beaconArray = new byte[8];

                        for(int i=beaconArray.length-1; i>=0; i--){
                            beaconArray[i] = (byte) beaconData;
                            beaconData >>>= 8;
                        }
                        beacon.setData(beaconArray);
                        byte[] beaconByte = beacon.toByte();
                        theRF.transmit(beaconByte);
                        //here is where we set our regular info
                        
                        nextBeacon = (theRF.clock() + extraTime[0]) + (long) 3000;
                        myState=State.NOUSE;
                        break;
                    }
                }
            }
            catch(InterruptedException e){
                // Do nothing
            }
        }
    }

    /**
     * Method to round the time to the nearest 50ms. So that we are only sending and doing other things when the clock is at the next 50ms 
     * interval. 
     * 
     * @param long the time that we are currently at. 
     * @return long the time that we want to wait until 
     */
    public long roundTime(long time){
        long remainder = time % 50;
        remainder=50-remainder;
        time+=remainder;
        return time;
    }
}