package wifi;
import java.util.concurrent.ArrayBlockingQueue;
import rf.RF;
import java.util.Map;
import java.util.HashMap;
import java.io.PrintWriter;
/**
 * This class is going to used as a thread to received packets 
 *
 * @Gennie Cheatham, Ricardo Barraza, Braden Ash
 * @November 7, 2019
 */
public class StreamReceiver implements Runnable
{
    public enum State{INUSE, NOUSE, INTERRUPTED};
    private State myState;
    // Array Blocking queue that is going to hold all incoming packets
    private ArrayBlockingQueue<Packets> receivedData;//array that holds received packets
    private ArrayBlockingQueue<Packets> ackData;//array that holds ack packets
    private ArrayBlockingQueue<Packets> ackReceived;//array that hold acks we received
    private RF theRF;           // The RF layer
    private PrintWriter output; // The output stream we'll write to
    private short ourMAC;       // Our MAC address
    private int[] status;       // Array holding status variable
    private long[] extraTime;   // Array holding extra time needed to be added for clock synchronization
    private int[] commands;     // Array holding commands and their values for the command terminal
    /**
     * Constructor for objects of class StreamSender
     */
    public StreamReceiver(ArrayBlockingQueue<Packets> receivedData, ArrayBlockingQueue<Packets> ackReceived, ArrayBlockingQueue<Packets> ackData, RF theRF, short ourMAC, PrintWriter output,int[] status,long[] extraTime, int[]commands)
    {
        this.receivedData = receivedData; 
        this.ackReceived = ackReceived;
        this.ackData = ackData;
        this.theRF = theRF;
        this.ourMAC = ourMAC;
        this.output = output; 
        this.status=status;
        this.extraTime=extraTime;
        this.commands = commands;
    }

    /**
     * Method continually runs to receive packets and add them to the ArrayBlockingQueue
     */
    public void run()
    {
        for(;;){
            //blocks until packet arrives
            byte[] packetreceived = theRF.receive();
            //creates an empty packet object
            Packets dataPacket = new Packets(ourMAC);
            int ourPacketSize = dataPacket.deconstructPacket(packetreceived);
            // If packet is not of sufficient size, status reflects error
            if(ourPacketSize==-1){
                status[0]=2;
            }
            // If packet received is a data packet and it is not a broadcast. Broadcasts do not require acknowledgment
            else if((dataPacket.getFrameType()==0)&&(dataPacket.getSourceAdd()!=-1)){
                try{
                    //ensure buffer is correct size, if too large incoming packet will be dropped
                    if(receivedData.size()<5 && receivedData.size()>=0){
                        receivedData.put(dataPacket);
                        // Create and set different values for the ackPacket that was received
                        Packets ackPacket = new Packets(ourMAC);
                        ackPacket.setFrameType((byte)2);
                        ackPacket.setSourceAdd(ourMAC);
                        ackPacket.setDestAdd(dataPacket.getSourceAdd());
                        ackPacket.setSequenceNum(dataPacket.getSequenceNum());
                        ackPacket.setControl(ackPacket.getFrameType(),(byte)0,ackPacket.getSequenceNum());
                        // Put packet onto queue
                        ackData.put(ackPacket);
                        // This conditional in various places indicates command set to debug mode and will output relevant debugging information
                        if(commands[1] == -1){
                            output.println("Queued incoming DATA packet with good CRC: <DATA" + dataPacket.getFrameType() + " " + dataPacket.getSourceAdd() + "--> " + dataPacket.getDestAdd() + ">");
                        }   
                    }
                    else{
                        status[0]=10;
                    }
                }catch(InterruptedException e){
                    //Do nothing
                }
            }
            // Packet is an Acknowledgment packet
            else if(dataPacket.getFrameType() == 1){
                //if the data packet is an ACK then we put it in the queue
                try{
                    ackReceived.put(dataPacket);
                }catch(InterruptedException e){
                }
            }
            // Packet holds a beacon
            else if(dataPacket.getFrameType() ==2){
                byte[] beaconData = dataPacket.getData();
                long timeStamp = 0;
                //we have to add in the time that it takes to receive and send beacons
                for(int i=0;i<beaconData.length;i++){
                    timeStamp<<=8;
                    timeStamp|= (beaconData[i]&0xFF);
                }  
                //Synchronize clock if beacon contains a timestamp ahead of current clock
                if(timeStamp > theRF.clock()+extraTime[0]){
                    this.extraTime[0]+=timeStamp-(theRF.clock());
                }
            }  
            // This conditional in various places indicates command set to debug mode and will output relevant debugging information
            if(commands[1] == -1){
                output.println("Receive has blocked, awaiting data");
            }
            //add ack packet to the array blocking queue
            final long SIFS = theRF.aSIFSTime;
            long sifswaited = 0;

            //after we receive the packet, build the acknowledgement and add it to the array blocking queue
            //and send after SIFS
            //do not want to send an Ack if the packet was a broadcast
            while(ackData.size()>0){
                int numInterrupt = 0;
                myState = State.NOUSE;
                try{
                    switch(myState){
                        //the channel is in use and therefore you cannot send the packet
                        case INUSE: 
                        //if channel is idle move to NOUSE state
                        if(theRF.inUse()==false){
                            myState = State.NOUSE;
                            //if channel is busy stay in INUSE
                        }else if(theRF.inUse() == true){ 
                            myState = State.INUSE;
                        }
                        break;
                        // The channel is not in use and now packets may be sent
                        case NOUSE: 
                        //if medium is idle and we have an ACK to send
                        if(theRF.inUse()==false && ackData.size()>0){
                            //start tracking SIFS
                            long startSIFS = theRF.clock()+extraTime[0];
                            while(theRF.inUse() == false && sifswaited<SIFS){
                                Thread.sleep(SIFS - sifswaited);
                                break;
                            }
                            long endSIFS = theRF.clock()+extraTime[0];
                            sifswaited += endSIFS - startSIFS;
                            //waited SIFS and the medium is still idle
                            if(sifswaited >= SIFS && theRF.inUse() == false){
                                //once we have waited the full amount of sifs, we reset sifs waited 
                                sifswaited = 0;
                                Packets packet = ackData.take();
                                if(packet==null){
                                    status[0]=7;
                                }
                                //building byte array for the ack packet
                                byte[] data = packet.toByteAck();
                                theRF.transmit(data);
                            }
                        }
                        break;
                        // An interruption in the transmission occured
                        case INTERRUPTED:
                        //sending was interrupted, increment the variable
                        numInterrupt++;
                        if(theRF.inUse() == true){
                            myState = State.INUSE;
                        }else{
                            myState = State.NOUSE;
                        }
                        break;
                    }
                }catch(InterruptedException e){
                    //DO nothing
                }
            }
        }
    }
}

