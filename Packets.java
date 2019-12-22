package wifi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.nio.ByteBuffer;

/**
 * This is the class that is going to build the packets. We have an array of bytes in which we are going to store the data that we are going to send. The short control
 * value is for the frame type, the retransmission bit and the sequence number which we will set in the setter and then that short becomes a part of our overall packet.
 * The next part of our packet is the destination short in which it contains the destination MAC address of the machine we are sending our packet to. The source address is
 * filled with our MAC address so that we can be sent acks. The byte array is next in which we are setting the data into a number of bytes. Finally we have an integer that
 * is the checksum which will make sure that the correct amount of data is being sent. Besides constructing the packets with out setters and getters we also have a method that moves all of the short and 
 * int values into a byte array so that we can send all of the data on the RF layer. In addition we have a deconstruct packet method in whch we are taking in an array of bytes
 * and then are being able to decontruct it and put it into the format that we want for a packet. 
 *
 * @Gennie Cheatham, Ricardo Barraza, Braden Ash
 * @November 7, 2019
 */
public class Packets
{
    private byte[] data; //data we want to send 
    private short control;//control value of the packet
    private short dest; //destination address
    private short source; //source address
    private long crc; //checksum of the array
    private short ourMAC; //our MAC address
    private byte frameType;//a byte value that indicates the type of frame we are sending
    private byte retransmit;//a bit that will be set if we are retransmitting
    private int sequenceNum;//helps ensure that we are sending the correct packets in order
    private ArrayList<byte[]> packetArray;//array in which we are storing the packets that we want to send
    private CRC32 crcMachine = new CRC32();//a machine to calculate the CRC and make sure that we have gotten all the data

    /**
     * Constructor for objects of class Packets to create empty packets
     */
    public Packets(short ourMAC){
        this.ourMAC = ourMAC;
    }

    /**
     * Constructor for objects of class Packets
     * 
     * @param short control value of the packet
     * @param short destination address
     * @param short source address
     * @param byte[] data
     * @param int crc
     */
    public Packets(short control, short dest, short source, byte[] data, long crc, short macAdd)
    {
        this.control = control;//has the frame type, retry and sequence#
        this.dest = dest;//destination address
        this.source = source;//source address
        this.data = data;//this holds the src address and the data to be sent 
        this.crc = crc;//checksum
        this.ourMAC = macAdd;
    }

    /**
     * Setter for Retransmission, either 0 or 1
     * @param byte restransmit bit
     */
    public void setRetransmission(byte x){
        this.retransmit=x;
    }

    /**
     * Getter for retransmission
     * @returns byte restransmit byte
     */
    public byte getRetransmission(){
        return this.retransmit;
    }

    /**
     * Setting the Control Value for our packets, based on the values it gets. This method performs bit shifting and and puts all the data into a short
     * @param byte frametype
     * @param byte retransmission
     * @param int sequenceNum
     * @return short control
     */
    public short setControl(byte frametype, byte retransmission, int sequenceNum){
        this.frameType = frametype;//0010 or 0000
        this.retransmit = retransmit;//0000 or 0001
        this.sequenceNum=sequenceNum;
        byte control1 = (byte)(frametype | retransmit);

        short sequenceNumber = (short) (sequenceNum % 4096);//making the sequence number into 12 bits
        short control2 = (short)(control1<<12);

        short control = (short) (control2|sequenceNumber);
        this.control=control;
        return control;
    }

    /**
     * Given a array of bytes, this method constructs the packet and bit shits the bytes in the array so that we can instantiate the variables of Packets
     * 
     * @param byte[] receivedData
     * @returns int the length of the data, returns -1 if the checksum is wrong meaning we did not receive the right data
     */
    public int deconstructPacket(byte[] receivedData){
        //converts the bytes in the array to an integer destination address
        //set the control 

        byte firstElement = receivedData[0];
        byte frameType = (byte)(firstElement >>5);
        setFrameType(frameType);

        short destAdd1 = (short)((receivedData[2] <<8)& 0xFF00);
        short destAdd2 = (short)((receivedData[3])& 0x00FF);
        short destAdd = (short)(destAdd2 | destAdd1);
        setDestAdd(destAdd);
        System.out.println("Destination Address: " + getDestAdd());
        System.out.println("Our Mac: " + ourMAC);
        //if the destination address isn't our MAC address then we want to ignore the packet 
        if(getDestAdd() == ourMAC || destAdd == -1){
            //converts the bytes in the array to an integer source address
            short sourceAdd1 = (short)((receivedData[4] <<8)& 0xFF00);
            short sourceAdd2 = (short)((receivedData[5])& 0x00FF);
            short sourceAdd = (short)(sourceAdd2 | sourceAdd1);
            setSourceAdd(sourceAdd);
            System.out.println("Source Address: " + getSourceAdd());

            //extracting and setting data 
            int index=6;
            byte[]data = new byte[(receivedData.length-10)];
            for(int i=0; i<data.length; i++){
                data[i]=receivedData[index];
                index++;
            }
            setData(data);

            //calculate our own crc for the data received
            crcMachine.reset();
            crcMachine.update(receivedData, 0, receivedData.length-4);
            //setCrc(crcMachine.getValue());
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.put(receivedData, receivedData.length-4, 4);

            int receivedCRC = buffer.getInt(0);
            //extract the crc from the packet we recieved

            long newCRC=0;
            if((receivedCRC<0)&&(receivedCRC!=-1)){
                //deals with the overlap of the CR
                newCRC = (Integer.MAX_VALUE+receivedCRC);
                newCRC = (Integer.MAX_VALUE+newCRC);
                newCRC+=2;
                //if there was an error in receiving the data then we want to do return -1 and not receiv
                if(newCRC!=crcMachine.getValue()){
                    return -1;
                }
            }
            else if((receivedCRC!=crcMachine.getValue())&&(receivedCRC!=-1)){
                return -1;
            }
            //check to see if they are equal
            return data.length;
        }
        return -1;
    }

    /**
     * Getter for source address.
     * @return short source address
     */
    public short getSourceAdd(){
        return source;
    }

    /**
     * Getter for destination address
     * @return short destination address
     */
    public short getDestAdd(){
        return dest;
    }

    /**
     * Getter for the sequence number.
     * @return int sequence number
     */
    public int getSequenceNum(){
        return sequenceNum;
    }

    /**
     * Getter for the control value.
     * @return short control
     */
    public short getControl(){
        return control;
    }

    /**
     * Getter for data
     * @return byte[] data
     */
    public byte[] getData(){
        return data;
    }

    /**
     * Getter for crc
     * @return long crc
     */
    public long getCrc(){
        return crc;
    }

    /**
     * Setter for source address.
     * @param short source address
     */
    public void setSourceAdd(short source){
        this.source=source;
    }

    /**
     * Setter for destination address
     * @param short source address
     */
    public void setDestAdd(short dest){
        this.dest=dest;
    }

    /**
     * Setter for data
     * @param byte[] buffer for the data
     */
    public void setData(byte[] buf){
        this.data=buf;
    }

    /**
     * Setter for crc
     * @param long crc
     */
    public void setCrc(long crc){
        this.crc=crc;
    }

    /**
     * Setter for frame type
     * @param byte frame type
     */
    public void setFrameType(byte frameType){
        this.frameType=frameType;
    }

    /**
     * Getter for frame type
     * @return byte frame type
     */
    public byte getFrameType(){
        return this.frameType;
    }

    /**
     * Setter for the sequence number
     * @param int sequence number
     */
    public void setSequenceNum(int sequenceNum){
        this.sequenceNum=sequenceNum;
    }

    /**
     * This method is shift attributes of the Packet class in order to send a packet that we are able to transmit
     * @return byte[] The packet put into byte[] array form
     */
    public byte[] toByte(){
        byte[] packet = new byte[data.length+10];
        //shift control value and put into packet
        short controlV = control;
        short controlV1 = (short) ((controlV & 0xFF00) >> 8);
        short controlV2 = (short) (controlV & 0x00FF);
        packet[0] = (byte) controlV1;
        packet[1] = (byte) controlV2;

        //shift destination address value and put into packet
        short destAddV = dest;
        short destAddV1 = (short) ((destAddV & 0xFF00) >> 8);
        short destAddV2 = (short) (destAddV & 0x00FF);
        packet[2] = (byte) destAddV1;
        packet[3] = (byte) destAddV2;

        //shift source address value and put into packet
        short sourceV = source;
        short sourceV1 = (short) ((sourceV & 0xFF00) >> 8);
        short sourceV2 = (short) (sourceV & 0x00FF);
        packet[4] = (byte) sourceV1;
        packet[5] = (byte) sourceV2;

        //set all the data
        for(int i=0;i<data.length;i++){
            packet[6+i]= data[i];
        }
        crcMachine.reset();
        crcMachine.update(packet, 0, packet.length-4);
        setCrc(crcMachine.getValue());

        //setting CRC
        long crcTemp = getCrc();
        ByteBuffer buff = ByteBuffer.allocate(4);
        buff = buff.putInt((int)crcTemp);
        
        
        
        //System.out.println("Temp: " + crcTemp);
        for (int i = 0; i <buff.position(); i++) {
            packet[i+packet.length-4] = buff.get(i);
        }
        return packet;
    }

    /**
     * This method is shift attributes of the Packet class in order to send a packet that we are able to transmit
     * @return byte[] The packet put into byte[] array form
     */
    public byte[] toByteAck(){
        byte[] packet = new byte[10];
        //shift control value and put into packet
        short controlV = control;
        short controlV1 = (short) ((controlV & 0xFF00) >> 8);
        short controlV2 = (short) (controlV & 0x00FF);
        packet[0] = (byte) controlV1;
        packet[1] = (byte) controlV2;

        //shift destination address value and put into packet
        short destAddV = dest;
        short destAddV1 = (short) ((destAddV & 0xFF00) >> 8);
        short destAddV2 = (short) (destAddV & 0x00FF);
        packet[2] = (byte) destAddV1;
        packet[3] = (byte) destAddV2;

        //shift source address value and put into packet
        short sourceV = source;
        short sourceV1 = (short) ((sourceV & 0xFF00) >> 8);
        short sourceV2 = (short) (sourceV & 0x00FF);
        packet[4] = (byte) sourceV1;
        packet[5] = (byte) sourceV2;

        //setting the crc
        for(int i=6;i<10;i++){
            packet[i]=(byte)-1;
        }
        return packet;
    }

    /**
     * To String for Packet Objects.
     * @return String the destination address, source address, the data, and the crc
     */
    public String toString(){
        String str = "Packet = " + getDestAdd() + " " + getSourceAdd() + " " + Arrays.toString(getData()) + " " + getCrc();
        return str;
    }

    /**
     * To String for Packet Objects.
     * @return String the destination address, source address, the data, and the crc
     */
    public String toStringAll(){
        String str = "Packet = " + getDestAdd() + " " + getSourceAdd() + " " + Arrays.toString(getData()) + " " + getCrc();
        return str;
    }

}

