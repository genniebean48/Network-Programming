package wifi;
import java.io.PrintWriter;
import java.util.ArrayList; 
import rf.RF;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.CRC32;

/**
 * Use this layer as a starting point for your project code.  See {@link Dot11Interface} for more
 * details on these routines.
 * @author richards, Gennie Cheatham, Ricardo Barraza, Braden Ash
 */
public class LinkLayer implements Dot11Interface 
{
    private RF theRF;           // You'll need one of these eventually
    private short ourMAC;       // Our MAC address
    private PrintWriter output; // The output stream we'll write to
    public ArrayBlockingQueue<Packets> sendData = new ArrayBlockingQueue<Packets>(100);     // Queue that holds the data packets to be sent
    public ArrayBlockingQueue<Packets> receivedData = new ArrayBlockingQueue<Packets>(100); // Queue that holds the data packets that have been received
    public ArrayBlockingQueue<Packets> ackSend = new ArrayBlockingQueue<Packets>(100);      // Queue that holds the ack packets to be sent
    public ArrayBlockingQueue<Packets> ackReceived = new ArrayBlockingQueue<Packets>(100);  // Queue that holds the ack packets that have been received
    private Map<Short, Integer> seqSend = new HashMap();//hashmap where the key is the destination address and the value is the sequence number we are on
    private Map<Short, Integer> seqRec = new HashMap();//hashmap where the key is the destination address and the value is the sequence number we are on
    private CRC32 crcMachine = new CRC32(); //CRC machine to help calculate checksums
    public int[] commands = new int[4];     // array that holds the commands and values for the command center
    public long time;                       // time variable for the host
    public int[] status = new int[]{3};     // array holding status variable
    public long[] extraTime=new long[]{0};  // array holding extra time variable to be used in clock synchronization
    /**
     * Constructor takes a MAC address and the PrintWriter to which our output will
     * be written.
     * @param ourMAC  MAC address
     * @param output  Output stream associated with GUI
     */
    public LinkLayer(short ourMAC, PrintWriter output) {
        this.ourMAC = ourMAC;
        this.output = output;   
        theRF = new RF(null, null);
        for(int i=0;i<commands.length;i++){
            commands[i]=0;
        }
        status[0]=1;
        (new Thread(new StreamSender(sendData,ackReceived, seqSend, ourMAC, theRF, output, commands,status,extraTime))).start();
        (new Thread(new StreamReceiver(receivedData,ackReceived,ackSend, theRF, ourMAC, output,status,extraTime, commands))).start();
        output.println("LinkLayer: Constructor ran.");
    }

    /**
     * Send method takes a destination, a buffer (array) of data, and the number
     * of bytes to send.  See docs for full description.
     * 
     * @param dest  destination address for packet
     * @param data  array holding data of packet
     * param len    number of bytes to be sent to destination
     * 
     * @return len  number of bytes that were sent to destination
     */
    public int send(short dest, byte[] data, int len) {
        output.println("LinkLayer: Sending "+ len +" bytes to "+dest);
        Packets packet = new Packets(ourMAC);

        //returns 0 if there is no space in the queue
        if(sendData.size() > 4){
            status[0]=10;
            return 0;
        }

        //if we have not sent to this address before then we want to put the address in the hashmap
        if(seqSend.containsKey(dest) == false){
            seqSend.put(dest, 0);
        }else{
            //if we have sent to the address before we want to increment the sequence number
            int currValue = seqSend.get(dest);
            if(currValue == Integer.MAX_VALUE){
                seqSend.replace(dest, 0);
            }else{
                seqSend.replace(dest, currValue+1);
            }
        }

        //from here we want to set the frame type, retry and sequence number. Once those are set we should have a method in 
        //packets that given those value, it will turn those into the control using the bitwise shiffting and set the control

        //setting the control of the packet
        //IF IT IS A RETRANSMISSION THEN FIX THE BYTE VALUE
        byte frametype = 0000;
        byte retransmit = 0000; 

        //sequence number 
        int sequenceNum = seqSend.get(dest);

        //set control 
        packet.setControl(frametype, retransmit, sequenceNum);
        //get control
        short control = packet.getControl();

        //extracting the source address from the data
        short sourceAdd = ourMAC;

        //creating the packets with the data we want to send
        if(len>2038){
            byte[] data1 = Arrays.copyOfRange(data, 0, 2038);
            long crc = -1;
            packet = new Packets(control,dest,sourceAdd, data1,crc,ourMAC);
        }else{
            //setting the crc 
            long crc = -1;
            packet = new Packets(control,dest,sourceAdd, data,crc,ourMAC);
        }

        //put the packet in the array blocking queue to send 
        try{
            //it is data so we put it in the send data array blocking queue
            sendData.put(packet);
            if(commands[1] == -1){
                output.println("Queuing " + data.length + " bytes for " + packet.getDestAdd());
            }
        }catch(InterruptedException e){
            // Do nothing
        }
        return len;
    }

    /**
     * Recv method blocks until data arrives, then writes it an address info into
     * the Transmission object.  See docs for full description.
     * 
     * @param t transmission object used to transmit and receive packets
     * 
     * @return data.getData().length    returns number of bytes of data received from packet
     */
    public int recv(Transmission t) {
        Packets data = new Packets(ourMAC);
        try {
            data = receivedData.take(); 
            //Setting all atributes of the Transmission object
            t.setSourceAddr(data.getSourceAdd());
            t.setDestAddr(data.getDestAdd());
            //if we have not received data from this address before put it in the hashmap
            if(seqRec.containsKey(t.getSourceAddr())==false){
                seqRec.put(t.getSourceAddr(), 0);
                output.println("First Address: " + t.getSourceAddr() + " Value: " + seqRec.get(t.getSourceAddr()));
            }else{
                //if we have sent to the address before we want to increment the sequence number
                int currValue = seqRec.get(t.getSourceAddr());
                seqRec.replace(t.getSourceAddr(), currValue+1);
                output.println("Address: " + t.getSourceAddr() + " Value: " + seqRec.get(t.getSourceAddr()));
                //if we have received data from them before then check the sequence number
                //if the sequence number does not match then print out a warning message
                //if it does match then you want to increment the sequence number you are expecting
            }
            t.setBuf(data.getData());
        }catch(InterruptedException e){
            //Do nothing
        }
        return data.getData().length; 
    }

    /**
     * Returns a current status code.  See docs for full description.
     * 
     * @return status[0]    returns value of current status
     */
    public int status() {
        if(status[0]==1){
            output.println("SUCCESS");
        }
        else if(status[0]==2){
            output.println("UNSPECIFIED_ERROR");
        }
        else if(status[0]==3){
            output.println("RF_INIT_FAILED");
        }
        else if(status[0]==4){
            output.println("TX_DELIVERED");
        }
        else if(status[0]==5){
            output.println("TX_FAILED");
        }
        else if(status[0]==6){
            output.println("BAD_BUF_SIZE");
        }
        else if(status[0]==7){
            output.println("BAD_ADDRESS");
        }
        else if(status[0]==8){
            output.println("BAD_MAC_ADDRESS");
        }
        else if(status[0]==9){
            output.println("ILLEGAL_ARGUMENT");
        }
        else if(status[0]==10){
            output.println("INSUFFICIENT_BUFFER_SPACE");
        }
        return status[0];
    }

    /**
     * Passes command info to your link layer.  See docs for full description.
     * 
     * @param cmd   the command that the user wants to take
     * @param val   the value of the command the user wants to take
     * 
     * @return 0    lets user know that command was successfully enacted and fit with Dot11 interface
     */
    public int command(int cmd, int val) {
        this.commands[cmd] = val;
        output.println("LinkLayer: Sending command "+cmd+" with value "+val);
        // Command outputs the choices and descriptions for commands the user may take
        if(cmd==0){
            output.println("------------Commands and Settings------------");
            output.println("Cmd #0: Display command options and current settings");
            output.println("Cmd #1: Set debug level.  Currently at 0");
            output.println("\tuse -1 forfull debug output, 0 for no output");
            output.println("Cmd #2: Set slot selection method.  Currently random");
            output.println("\tUse 0 for random slot selection, any other value to use maxCW");
            output.println("Cmd #3: Set beacon interval.  Currently at 3 seconds");
            output.println("\tValue specifies seconds between the start of beacons; -1 disables");
            output.println("---------------------------------------------");
        }
        //Command sets debug value and determines whether output will print debug statements or not
        else if(cmd==1){
            if(val==-1){
                //setting to debug mode
                output.println("Setting debug to -1");
            }
            else if(val==0){
                //setting to not debug mode
                output.println("Setting debug to 0");
            }
        }
        // Command determines slot interval for host, the value determines how many seconds the interval is
        else if(cmd==2){
            if(val == 0){
                output.println("Setting slot interval to be random.");
            }
            else{
                output.println("Setting slot interval to max current window size.");
            }
        }
        // Command sets beacons to either be disabled or set the timeframe of beacon transmission
        else if(cmd==3){
            if(val == -1){
                output.println("Disabling beacons.");
            }
            else{
                output.println("Beacon timeframe set to " + val + ".");
            }
        }else{
            status[0]=9;
        }
        return 0;
    }
}
