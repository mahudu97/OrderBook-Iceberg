import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

    public Order(int id, short price, int quantity) {
        this.id = id;
        this.price = price;
        this.quantity = quantity;
    }

    public int getId() {
        return this.id;
    }

    public short getPrice() {
        return this.price;
    }

    public int getQuantity() {
        return this.quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public abstract int trade(Order against);
    public abstract void requestTrade(Integer amount);

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
       super(id, price, quantity);
    }

    public int trade(Order against) {
        Integer amount = Math.min(this.getQuantity(), against.getQuantity());

        this.setQuantity(this.getQuantity()-amount);
        against.requestTrade(amount);
        return amount;
    }

    public void requestTrade(Integer amount) {
        assert amount <= this.getQuantity();
        this.setQuantity(this.getQuantity()-amount);
    }
}

class IcebergOrder extends Order {
    private int total_quantity;
    private int peak_size;

    public IcebergOrder(int id, short price, int quantity, int peak_size) {
        super(id, price, peak_size);
        this.total_quantity = quantity;
        this.peak_size = peak_size;
    }

    public int trade(Order against) {
        Integer amount = Math.min(this.getQuantity(), against.getQuantity());

        this.total_quantity -= amount;
        against.requestTrade(amount);

        // since this method is only called when a new Iceberg is being entered,
        // show peak size, is possible, regardless of trade amount
        Integer showQuantity = (total_quantity > peak_size) ? peak_size : total_quantity; 
        this.setQuantity(showQuantity);

        return amount;
    }

    public void requestTrade(Integer amount) {
        assert amount <= this.getQuantity();
        this.setQuantity(this.getQuantity()-amount);
        this.total_quantity -= amount;

        if (this.getQuantity() == 0 && total_quantity > 0) {
            this.setQuantity(this.peak_size);
        }
    }
}

class TradeMessage {
    private int buy_id;
    private int sell_id;
    private short price;
    private int quantity;

    public TradeMessage(int buy_id, int sell_id, short price, int quantity) {
        this.buy_id = buy_id;
        this.sell_id = sell_id;
        this.price = price;
        this.quantity = quantity;
    }

    public void updateQuantity(int amount) {
        this.quantity += amount;
    }

    public String toStr() {
        return String.valueOf(buy_id)+","+String.valueOf(sell_id)+","+String.valueOf(price)+","+String.valueOf(quantity);
    }
}

class OrderBook {
    // Map price to list of orders.
    private TreeMap<Short, List<Order>> bids, asks;

    public OrderBook() {
        bids = new TreeMap<>(Comparator.reverseOrder());
        asks = new TreeMap<>();
    }

    private static class ParseOrderResult {
        private boolean isBuy;
        private Order order;
    }

    // assumes order is a csv order (Limit or Iceberg)
    private ParseOrderResult parseOrderLine(String order) {
        ParseOrderResult por = new ParseOrderResult();
        Scanner reader = new Scanner(order);
        reader.useDelimiter(",");

        char buyOrSell = reader.next().charAt(0);
        por.isBuy = (buyOrSell == 'B');

        int id = reader.nextInt();
        short price = reader.nextShort();
        int quantity = reader.nextInt();

        if (reader.hasNextInt()) {
            por.order = new IcebergOrder(id, price, quantity, reader.nextInt());
        }
        else {
            por.order = new LimitOrder(id, price, quantity);
        }

        reader.close();
        return por;
    }

    // Adds an order to a book (bids or asks) if not filled.
    private void addOrderToBook(Order order, TreeMap<Short, List<Order>> book) {
        if (order.getQuantity() != 0) {
            Short price = order.getPrice();
            if (!book.containsKey(price)) {
                book.put(price, new LinkedList<Order>());
            }
            book.get(price).add(order);
        }
    }

    private void tradeAtPrice(Short price, Order fillOrder, List<Order> ordersAtThisPrice, List<String> tradeLog) {
        Map<Integer, TradeMessage> traders = new LinkedHashMap<Integer, TradeMessage>();

        while (!ordersAtThisPrice.isEmpty()) {
            Order orderInBook = ordersAtThisPrice.get(0);
            Integer trader = orderInBook.getId();
            // match at this price until exhausted price point, or order filled
            if (fillOrder.getQuantity() == 0) {
                break;
            }

            Integer tradeAmount = fillOrder.trade(orderInBook);

            if (traders.containsKey(trader)) {
                traders.get(trader).updateQuantity(tradeAmount);
            }
            else {
                traders.put(trader, new TradeMessage(fillOrder.getId(), trader, price, tradeAmount));
            }

            // place order from back at back of the list if not fully 
            if (orderInBook.getQuantity() != 0) {
                ordersAtThisPrice.add(orderInBook);
            }
            // pop order from head
            ordersAtThisPrice.remove(0);
        }

        for(Map.Entry<Integer,TradeMessage> entry : traders.entrySet()) {
            tradeLog.add(entry.getValue().toStr());
        }
    }

    // Modifies this OrderBook bids and asks maps
    // Will request IcebergOrder objects to update if traded.
    // Returns list of reulting trades.
    private List<String> matchEngine(ParseOrderResult por) {
        List<String> trades = new LinkedList<String>();

        if (por.isBuy) {
            for(Map.Entry<Short,List<Order>> entry : asks.entrySet()) {
                Short price = entry.getKey();
                // iterate through sells until price > order.price or order filled
                if (price > por.order.getPrice() || por.order.getQuantity() == 0) {
                    break;
                }
                List<Order> ordersAtThisPrice = entry.getValue();

                tradeAtPrice(price, por.order, ordersAtThisPrice, trades);
            }
            // if not entirely filled - add to bids book
            addOrderToBook(por.order, bids);
        }
        else {
            for(Map.Entry<Short,List<Order>> entry : bids.entrySet()) {
                Short price = entry.getKey();
                // iterate through sells until price < order.price or order filled
                if (price < por.order.getPrice() || por.order.getQuantity() == 0) {
                    break;
                }
                List<Order> ordersAtThisPrice = entry.getValue();

                tradeAtPrice(price, por.order, ordersAtThisPrice, trades);
            }
            // if not entirely filled - add to asks book
            addOrderToBook(por.order, asks);
        }

        return trades;
    }

    public static void main(String[] args) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        OrderBook book = new OrderBook();
        String line;

        try {
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) == 'B' || line.charAt(0) == 'S') {
                    ParseOrderResult por = book.parseOrderLine(line);

                    List<String> resultingTradeMsgs = book.matchEngine(por);

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
        for(Map.Entry<Short,List<Order>> entry : bids.entrySet()) {
            for (Order order : entry.getValue()) {
                all_bids.add(order.prettyPrintBuy());
            }
        }
        List<String> all_asks = new ArrayList<String>();
        for(Map.Entry<Short,List<Order>> entry : asks.entrySet()) {
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