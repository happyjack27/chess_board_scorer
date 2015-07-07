
/*
creates scored bit boards 

each bit board is is in binary as 14 longs:
long[]{
score,
white pawns, white knights, white bishops, white rooks, white queens, white kings,
black pawns, black knights, black bishops, black rooks, black queens, black kings,
-1L
}

it is always white to move. (when its black to move, the board is flipped,the colors swapped, and the score inverted)

*/
/*
// must first convert pgn files(S) to uci notation.
// pgn-extract -Wuci -oout.pgn games.pgn
// /Users/jimbrill/NetBeansProjects/cuda-convnet/pgn-extract/pgn-extract -Wuci -oout.pgn /Users/jimbrill/NetBeansProjects/cuda-convnet/chess_boardscorer/data/ficsgamesdb_201501_standard_nomovetimes_1264408.pgn


*/

package chess_board_scorer;

import java.io.*;
import java.util.*;
import java.util.logging.*;
import java.nio.*;
import java.nio.file.*;

/**
 *
 * 
 */

/*
toadd:
function to read in moves, convert to vector
function to send it to stockfish
function to parse the result
function to dump to scored bitvector

//bit board 0 didn't process en passant!
*/
public class Chess_board_scorer {
    static boolean score_out_only = true;
    static final String PATH_TO_STOCKFISH = "/Users/jimbrill/NetBeansProjects/Stockfish/src/stockfish";
    static final String PATH_TO_DATA_DIRTY = "/Users/jimbrill/Downloads/out.pgn";
    static final String PATH_TO_DATA_CLEAN = "/Users/jimbrill/Downloads/out201502_all.pgn";

    //static final String PATH_TO_DATA_DIRTY = "/Users/jimbrill/NetBeansProjects/cuda-convnet/chess_boardscorer/data/out.pgn";
    //static final String PATH_TO_DATA_CLEAN = "/Users/jimbrill/NetBeansProjects/chess_board_scorer_data/chess_board_scorer_data/data/out2.pgn";
///Users/jimbrill/NetBeansProjects/cuda-convnet/chess_boardscorer/data/out2.pgn";
    static final String PATH_TO_RATED_BITBOARD_OUT = "/Users/jimbrill/NetBeansProjects/chess_board_scorer_data/chess_board_scorer_data/data/201502_all/bitboards";
///Users/jimbrill/NetBeansProjects/cuda-convnet/chess_boardscorer/data/bitboards";//.bin";
    static int time_per_move = 25; //in milliseconds
    static int start_on_file = 43;//3;
    static int end_on_file = 10000;
    static int GAMES_PER_FILE = 1000;

    static Process processStockfish = null;
    static InputStream streamFromStockFish = null;
    static OutputStream streamToStockFish = null;
      // Piece types
  static byte PAWN = 1;
  static byte KNIGHT = 2;
  static byte BISHOP = 3;
  static byte ROOK = 4;
  static byte QUEEN = 5;
  static byte KING = 6;
  static byte WHITE = 0;
  static byte BLACK = 6;
  
  static char CPAWN = 'p';
  static char CKNIGHT = 'k';
  static char CBISHOP = 'b';
  static char CROOK = 'r';
  static char CQUEEN = 'q';

  public static int[][] board = new int[8][8];
  
  static int en_passant_x = -1;
  static int en_passant_y = -1;
  static int en_passant_y_take = -1;
  static boolean double_pawn_push = false;
  
  
    public static void main(String[] args) {
        createRatedBitBoards();
        System.exit(0);
        cleandata();
        System.exit(0);
    }
  
  public static String toStockFishDepth(String moves, int depth) {
      return ""
              +"position startpos moves "+moves+"\n"
              +"go depth "+depth+"\n"
              ;
  }
  
  public static String toStockFishTime(String moves, int time) {
      return ""
              +"position startpos moves "+moves+"\n"
              +"go movetime "+time+"\n"
              ;
  }
  public static int extractScore(String output) {
      try {
        String[] words = output.split(" ");
        int i = 0;
        if( words[0].equals("cp")) {
            if( words[1].equals("-")) {
                return -Integer.parseInt(words[2]);
            } else {
                return Integer.parseInt(words[1]);
            }
        } else
        if( words[0].equals("mate")) {
            if( words[1].equals("-")) {
                return Integer.parseInt(words[2]) > 0 ? -100*100 : 100*100;
            } else {
                try {
                  return Integer.parseInt(words[1]) < 0 ? -100*100 : 100*100;
                } catch (Exception ex) {
                  return 0;
                }
            }

        }
      } catch (Exception ex) { }
      return 0;
  }
  
  public static void boardout(int[][] board) {
      System.out.println();
      for( int i = 0; i < board.length; i++) {
        for( int j = 0; j < board.length; j++) {
            if( board[i][j] == 0) {
                System.out.print("."+" ");
            } else {
                System.out.print(board[i][j]+" ");
            }
        
        }
        System.out.println();
      }
      
  }
  
  public static void startpos(int[][] board) {
      double_pawn_push = false;
      for( int i = 0; i < board.length; i++) {
        for( int j = 0; j < board.length; j++) {
            board[i][j]=0;
        }
      }
      board[0][0]=board[7][0]=ROOK;
      board[1][0]=board[6][0]=KNIGHT;
      board[2][0]=board[5][0]=BISHOP;
      board[3][0]=QUEEN;
      board[4][0]=KING;
      for( int i = 0; i < board.length; i++) {
          board[i][1] = WHITE+PAWN;
      }
      
      for( int i = 0; i < board.length; i++) {
          board[i][7] = board[i][0]+(BLACK-WHITE);
          board[i][6] = board[i][1]+(BLACK-WHITE);
      }
  }
    /*
  
The move format is in long algebraic notation.
A nullmove from the Engine to the GUI should be send as 0000.
Examples:  e2e4, e7e5, e1g1 (white short castling), e7e8q (for promotion)
cp - centipawns - is the answer.
  */
  // a b c d    e f g h
  //TODO: need to add en passant!
  public static void move(int[][] board, String move) {
      int x0 = move.charAt(0)-'a';
      int y0 = move.charAt(1)-'1';
      int x1 = move.charAt(2)-'a';
      int y1 = move.charAt(3)-'1';
      
      //process en passant
      if( double_pawn_push && x1 == en_passant_x && y1 == en_passant_y && x0 != x1 && (board[x0][y0] == PAWN || board[x0][y0] == PAWN+BLACK)) {
          board[en_passant_x][en_passant_y_take] = 0;
      }
      if( board[x0][y0] == PAWN && y0 == 1 && y1 == 3 && x0 == x1) {
          double_pawn_push = true;
          en_passant_x = x0;
          en_passant_y = 2;
          en_passant_y_take = 3;
      } else 
      if( board[x0][y0] == PAWN+BLACK && y0 == 6 && y1 == 4 && x0 == x1) {
          double_pawn_push = true;
          en_passant_x = x0;
          en_passant_y = 5;
          en_passant_y_take = 4;
      } else {
          double_pawn_push = false;
      }
      
      //process castling
      if( board[x0][y0] == KING || board[x0][y0] == KING+BLACK) {
        board[x1][y1]=board[x0][y0];
        board[x0][y0] = 0;

        if( move.equals("e1g1")) { //white short castling
          move(board,"h1f1");
        } else
        if( move.equals("e1c1")) { //white long castling
          move(board,"a1d1");
        } else
        if( move.equals("e7g7")) { //black short castling
          move(board,"h7f7");
        } else
        if( move.equals("e7g7")) { //black long castling
          move(board,"a7d7");
        }
        return;
      }
      
      board[x1][y1]=board[x0][y0];
      board[x0][y0] = 0;
      
      //process pawn promotion
      if( move.length()>4) {
          char c = move.charAt(4);
          board[x1][y1] = c == CQUEEN ? QUEEN : c == CKNIGHT ? KNIGHT : board[x1][y1];
      } //a b c d  - e f g h
  }
  public static void mirror(int[][] board, int[][] dest) {
      for( int i = 0; i < 8; i++) {
        for( int j = 0; j < 8; j++) {
            dest[7-i][j] = board[i][j];
        }
      }
  }
  public static void swap_colors(int[][] board, int[][] dest) {
      for( int i = 0; i < 8; i++) {
        for( int j = 0; j < 8; j++) {
            if(board[i][j] == 0) {
                dest[i][7-j] = 0;
            } else if(board[i][j] > BLACK) {
                dest[i][7-j] = board[i][j]-BLACK;
            } else {
                dest[i][7-j] = board[i][j]+BLACK;
            }
        }
      }
      
  }
      
  public static long[] to_bit_board(int[][] board) {
    long[] bits = new long[]{0L, 0L,0L,0L,0L,0L,0L, 0L,0L,0L,0L,0L,0L};
    long mask = 1L;
    for( int i = 0; i < 8; i++) {
      for( int j = 0; j < 8; j++) {
          int layer = board[i][j];
          if( layer > 0) {
            bits[layer] |= mask;
          }
          mask <<= 1L;
      }
    }
    return bits;
  }
  public static void moves(int[][] board, String moves) {
      String[] m = moves.trim().split(" ");
      for( String s : m) {
          move(board,s);
      }
  }
  
  public static void createRatedBitBoards() {
        FileOutputStream fos;
        FileReader fis;
        DataOutputStream dos;
        try {
            fis = new FileReader(new File(PATH_TO_DATA_CLEAN));
            BufferedReader bufferedReader = new BufferedReader(fis);
            //StringBuffer stringBuffer = new StringBuffer();
            String line;
            String[] words; 
            StringBuilder sb;
            connect();
            int[][] flipped_board = new int[8][8];
            int[][] mirrored_board = new int[8][8];
            
            int c = 0;
            int out_file = 0;

            while ((line = bufferedReader.readLine()) != null && out_file < start_on_file) {
                c++;
                if( c % GAMES_PER_FILE == 0) {
                    out_file++;
                }
            }
            
            fos = new FileOutputStream(new File(PATH_TO_RATED_BITBOARD_OUT+out_file+".bin"));
            dos = new DataOutputStream(fos);

            while ((line = bufferedReader.readLine()) != null) {
                String moves_full = line;

                //int score = score(moves);
                //System.out.println("score: "+score);
                //mirror(board);
                //int[][] temp = new int[8][8];
                //swap_colors(board,temp);
                //boardout(board);
                // TODO code application logic here
                String[] moves = moves_full.split(" ");
                StringBuilder moves_partial = new StringBuilder(""+moves[0]);
                startpos(board);
                moves(board,moves[0]);
                if( score_out_only) {
                    int score = score(moves_partial.toString());
                    fos.write((""+score).getBytes());
                }
                for( int i = 1; i < moves.length; i++) {
                    moves_partial.append(" ");
                    moves_partial.append(moves[i]);
                    moves(board,moves[i]);
                    int score = score(moves_partial.toString());
                    //boardout(board);
                    if( score_out_only) {
                        fos.write((" "+score).getBytes());
          
                    } else {

                    
                    if( i % 2 == 0) {
                        //score = -score; //no need to flip score because its already from perspective of engine.
                        swap_colors(board,flipped_board);
                        
                        mirror(flipped_board,mirrored_board);
                        long[] res1 = to_bit_board(flipped_board);
                        res1[0] = (long)score;
                        for( int j = 0; j < res1.length; j++) { 
                            dos.writeLong(res1[j]);
                        }
                        dos.writeLong(-1L);
                        long[] res2 = to_bit_board(mirrored_board);
                        res2[0] = (long)score;
                        for( int j = 0; j < res2.length; j++) { 
                            dos.writeLong(res2[j]);
                        }
                        dos.writeLong(-1L);
                        
                    } else {
                        mirror(board,mirrored_board);
                        long[] res1 = to_bit_board(board);
                        res1[0] = (long)score;
                        for( int j = 0; j < res1.length; j++) { 
                            dos.writeLong(res1[j]);
                        }
                        dos.writeLong(-1L);
                        long[] res2 = to_bit_board(mirrored_board);
                        res2[0] = (long)score;
                        for( int j = 0; j < res2.length; j++) { 
                            dos.writeLong(res2[j]);
                        }
                        dos.writeLong(-1L);
                        
                    }
                }
                }
                if( score_out_only) {
                    fos.write('\n');
                } else {
                    
                }

 
                c++;
                if( c % 10 == 0) {
                    System.out.print(".");
                }
                if( c % GAMES_PER_FILE == 0) {
                    try {
                        dos.flush();
                        fos.flush();

                        dos.close();
                        fos.close();
                    } catch (Exception ex) { } 
                    
                    out_file++;
                    if( out_file >= end_on_file) {
                        break;
                    }
                    
                    fos = new FileOutputStream(new File(PATH_TO_RATED_BITBOARD_OUT+out_file+".bin"));
                    dos = new DataOutputStream(fos);

                    System.out.println(""+c+" games");
                }
                if( c % 10000 == 0) {
                    System.out.println("--- "+c+" games");
                }
            }
            disconnect();
            fis.close();
            dos.flush();
            fos.flush();
            fos.close();
        } catch (Exception ex) {
            Logger.getLogger(Chess_board_scorer.class.getName()).log(Level.SEVERE, null, ex);
        }
    
  }
  
  public static void cleandata() {
      FileReader fis;
      FileOutputStream fos;
      
        try {
            fis = new FileReader(new File(PATH_TO_DATA_DIRTY));
            fos = new FileOutputStream(new File(PATH_TO_DATA_CLEAN));
            BufferedReader bufferedReader = new BufferedReader(fis);
            //StringBuffer stringBuffer = new StringBuffer();
            String line;
            String[] words;
            StringBuilder sb;
            int c = 0;
            while ((line = bufferedReader.readLine()) != null) {
                if( !line.contains("[") && line.length() > 8) {
                    words = line.split(" ");
                    sb = new StringBuilder();
                    sb.append(words[0]);
                    for( int i = 1; i < words.length-1; i++) {
                        sb.append(" "+words[i]);
                    }
                    sb.append("\n");
                    fos.write(sb.toString().getBytes());
                    //fos.write(line.getBytes());
                    //fos.write('\n');
                    //System.out.println(sb.toString());
                    c++;
                    if( c % 100 == 0) {
                        System.out.print(".");
                    }
                    if( c % 5000 == 0) {
                        System.out.println();
                    }
                }
            }
            fis.close();
            fos.flush();
            fos.close();
        } catch (Exception ex) {
            Logger.getLogger(Chess_board_scorer.class.getName()).log(Level.SEVERE, null, ex);
        }
  }

    /**
     * @param args the command line arguments
     */
  
    public static void process_game(String moves_full) {
        String[] moves = moves_full.split(" ");
        StringBuilder moves_partial = new StringBuilder(""+moves[0]);
        startpos(board);
        moves(board,moves[0]);
        for( int i = 1; i < moves.length; i++) {
            moves_partial.append(" ");
            moves_partial.append(moves[i]);
            moves(board,moves[i]);
            int score = score(moves_partial.toString());
            boardout(board);
            System.out.println("score: "+score);   
            System.out.println("to move: "+(i % 2 == 1 ? "white" : "black"));   
        }
    }
    public static int score(String moves) {
        try {
            streamToStockFish.write(toStockFishTime(moves,time_per_move).getBytes());
            streamToStockFish.flush();
        } catch (IOException ex) {
            Logger.getLogger(Chess_board_scorer.class.getName()).log(Level.SEVERE, null, ex);
        }
        StringBuilder sb = new StringBuilder();
        String s = null;
        boolean found = false;
        try {
            while(true) {//streamFromStockFish.available() > 0 || !found) {
                try {
                    //Thread.sleep(5);
                } catch (Exception ex) {
                    Logger.getLogger(Chess_board_scorer.class.getName()).log(Level.SEVERE, null, ex);
                }
                byte[] bb = new byte[streamFromStockFish.available()];
                streamFromStockFish.read(bb);
                sb.append(new String(bb));
                s = sb.toString();
                if( s.contains("bestmove")) {
                    break;
                }
            }
            String[] ss = s.split("score");
            s = ss[ss.length-1].trim();
            //System.out.print(s);
            return extractScore(s);
        } catch (Exception ex) {
            Logger.getLogger(Chess_board_scorer.class.getName()).log(Level.SEVERE, null, ex);
        }
        return 0;
    }
    
    //http://docs.oracle.com/javase/7/docs/api/java/lang/Process.html
    public static void disconnect() {
        try {
            streamToStockFish.write("quit\n".getBytes());
            streamToStockFish.flush();
        } catch (IOException ex) {
            Logger.getLogger(Chess_board_scorer.class.getName()).log(Level.SEVERE, null, ex);
        }

    }
    public static void connect() {
        processStockfish = null;
        try {
            processStockfish = Runtime.getRuntime().exec(PATH_TO_STOCKFISH);
        } catch (IOException ex) {
            Logger.getLogger(Chess_board_scorer.class.getName()).log(Level.SEVERE, null, ex);
        }
        streamFromStockFish = processStockfish.getInputStream();
        streamToStockFish = processStockfish.getOutputStream();
        try {
            streamToStockFish.write("isready\n".getBytes());
            streamToStockFish.flush();
        } catch (IOException ex) {
            Logger.getLogger(Chess_board_scorer.class.getName()).log(Level.SEVERE, null, ex);
        }
        
    }
    
}
