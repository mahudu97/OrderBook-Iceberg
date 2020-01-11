import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

abstract class Order {
    private int id;
    private short price;
    private int quantity;

    private static final int ID_PAD = 10;
    private static final int VOLUME_PAD = 13;
    private static final int PRICE_PAD = 7;

    private static String padLeft(String s, int n) {
        return String.format("%" + n + "s", s);  
    }

    public String prettyPrintBuy() {
        String p = NumberFormat.getNumberInstance(Locale.US).format(price);
        String q = NumberFormat.getNumberInstance(Locale.US).format(quantity);
        String ident = String.valueOf(id);

        return padLeft(ident, ID_PAD)+"|"+padLeft(q, VOLUME_PAD)+"|"+padLeft(p, PRICE_PAD);
    }

    public String prettyPrintSell() {
        String p = NumberFormat.getNumberInstance(Locale.US).format(price);
        String q = NumberFormat.getNumberInstance(Locale.US).format(quantity);
        String ident = String.valueOf(id);

        return padLeft(p, PRICE_PAD)+"|"+padLeft(q, VOLUME_PAD)+"|"+padLeft(ident, ID_PAD);
    }
}

class LimitOrder extends Order {
    public LimitOrder(int id, short price, int quantity) {
        id = id;
        price = price;
        quantity = quantity;
    }
}

class IcebergOrder extends Order {
    private int total_quantity;
    private int peak_size;

    public IcebergOrder(int id, short price, int quantity, int peak_size) {
        id = id;
        price = price;
        quantity = quantity;
        total_quantity = quantity;
        peak_size = peak_size;
    }
}

class OrderBook {
    // Map price to list of orders.
    private TreeMap<Integer, List<Order>> bids, asks;

    public OrderBook() {
        bids = new TreeMap<>(Comparator.reverseOrder());
        asks = new TreeMap<>();
    }

    private String makeTradeMessage (int buy_id, int sell_id, short price, int quantity) {
        return String.valueOf(buy_id)+","+String.valueOf(sell_id)+","+String.valueOf(price)+","+String.valueOf(quantity);
    }

    private static class ParseOrderResult {
        boolean isBuy;
        Order order;
    }

    // method assumes order is a csv order (Limit or Iceberg)
    private ParseOrderResult parseOrderLine(String order) {
        ParseOrderResult pr = new ParseOrderResult();
        Scanner reader = new Scanner(order);
        reader.useDelimiter(",");

        char buyOrSell = reader.next().charAt(0);
        pr.isBuy = (buyOrSell == 'B');

        int id = reader.nextInt();
        short price = reader.nextShort();
        int quantity = reader.nextInt();

        // iceberg
        if (reader.hasNextInt()) {
            pr.order = new IcebergOrder(id, price, quantity, reader.nextInt());
        }
        // limit
        else {
            pr.order = new LimitOrder(id, price, quantity);
        }

        reader.close();
        return pr;
    }

    // Modifies this OrderBook bids and asks maps
    // Will request IcebergOrder objects to update if traded.
    // Returns list of reulting trades.
    private List<String> matchEngine(ParseOrderResult por) {
        List<String> trades = new LinkedList<String>();

        return trades;
    }

    public static void main(String[] args) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        OrderBook book = new OrderBook();
        String line;

        try {
            while ((line = reader.readLine()) != null) {
                if (line.charAt(0) == 'B' || line.charAt(0) == 'S') {
                    ParseOrderResult por = book.parseOrderLine(line);
                    List<String> resultingTradeMsgs;

                    resultingTradeMsgs = book.matchEngine(por);

                    for (String tm : resultingTradeMsgs) {
                        System.out.println(tm);
                    }

                    System.out.print(book.prettyPrint());
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading line from stdin");
        }

        try {
            reader.close();
        } catch (IOException e) {
            System.err.println("Error closing BufferedStream to stdin");
            e.printStackTrace();
        }
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);  
    }

    private static String padLeft(String s, int n) {
        return String.format("%" + n + "s", s);  
    }

    private static final int BUY_PAD = 32;
    private static final int SELL_PAD = 32;
    private static final int ID_PAD = 10;
    private static final int VOLUME_PAD = 13;
    private static final int PRICE_PAD = 7;

    public String prettyPrint() {
        String table = "";

        // add header
        table += "+" + String.join("", Collections.nCopies(65, "-")) + "+\n";
        table += "|" + padRight(" BUY", BUY_PAD) + "|" + padRight(" SELL", SELL_PAD) + "|\n";
        table += "|" + padRight(" Id", ID_PAD);
        table += "|" + padRight(" Volume", VOLUME_PAD);
        table += "|" + padRight(" Price", PRICE_PAD);
        table += "|" + padRight(" Price", PRICE_PAD);
        table += "|" + padRight(" Volume", VOLUME_PAD);
        table += "|" + padRight(" Id", ID_PAD);
        table += "|\n";
        table += "+----------+-------------+-------+-------+-------------+----------+\n";

        // collect global, ordered list of bids and asks
        List<String> all_bids = new ArrayList<String>();
        for(Map.Entry<Integer,List<Order>> entry : bids.entrySet()) {
            for (Order order : entry.getValue()) {
                all_bids.add(order.prettyPrintBuy());
            }
        }
        List<String> all_asks = new ArrayList<String>();
        for(Map.Entry<Integer,List<Order>> entry : asks.entrySet()) {
            for (Order order : entry.getValue()) {
                all_asks.add(order.prettyPrintSell());
            }
        }

        Iterator<String> bids_it = all_bids.iterator();
        Iterator<String> asks_it = all_asks.iterator();

        while (bids_it.hasNext() || asks_it.hasNext()) {
            table += "|";
            if (bids_it.hasNext()) {
                table += bids_it.next();
            }
            else {
                table += padLeft("", ID_PAD) + "|";
                table += padLeft("", VOLUME_PAD) + "|";
                table += padLeft("", PRICE_PAD);
            }
            table += "|";
            if (asks_it.hasNext()) {
                table += asks_it.next();
            }
            else {
                table += padLeft("", PRICE_PAD) + "|";
                table += padLeft("", VOLUME_PAD) + "|";
                table += padLeft("", ID_PAD);
            }
            table += "|\n";
        }

        // add bottom border
        table += "+" + String.join("", Collections.nCopies(65, "-")) + "+\n";

        return table;
    }
}