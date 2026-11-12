package model;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@WebServlet("/ReservationServlet")
public class ReservationServlet extends HttpServlet {

    private static final String INSERT_SQL = """
            INSERT INTO reservations (
                user_name, user_role, item_category, quantity, booking_days,
                daily_rate, subtotal, net_total, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.sendRedirect(request.getContextPath() + "/booking_form.html");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("text/html;charset=UTF-8");

        // 1. Read and validate the form.
        String name = request.getParameter("userName");
        String role = request.getParameter("userRole");
        String item = request.getParameter("itemCategory");

        if (name == null) {
            showError(response, 400, "Enter your full name.");
            return;
        }

        name = name.trim().toUpperCase(Locale.ROOT);

        if (name.isBlank() || name.codePointCount(0, name.length()) > 100) {
            showError(response, 400, "Enter a name between 1 and 100 characters.");
            return;
        }

        if (!"STUDENT".equals(role) && !"FACULTY".equals(role)) {
            showError(response, 400, "Choose Student or Faculty.");
            return;
        }

        if (!"LAPTOP".equals(item) && !"PROJECTOR".equals(item) && !"POD".equals(item)) {
            showError(response, 400, "Choose Laptop, Projector, or Study pod.");
            return;
        }

        int quantity;
        int days;

        try {
            quantity = Integer.parseInt(request.getParameter("quantity"));
            days = Integer.parseInt(request.getParameter("bookingDays"));
        } catch (NumberFormatException e) {
            showError(response, 400, "Quantity and booking days must be whole numbers.");
            return;
        }

        if (quantity < 1 || days < 1) {
            showError(response, 400, "Quantity and booking days must be at least 1.");
            return;
        }

        // 2. Calculate the price and faculty discount.
        BigDecimal dailyRate = switch (item) {
            case "LAPTOP" -> new BigDecimal("50.00");
            case "PROJECTOR" -> new BigDecimal("80.00");
            case "POD" -> new BigDecimal("100.00");
            default -> throw new IllegalStateException("Item was already validated.");
        };

        BigDecimal subtotal = dailyRate
                .multiply(BigDecimal.valueOf(quantity))
                .multiply(BigDecimal.valueOf(days));

        if (subtotal.compareTo(new BigDecimal("99999999.99")) > 0) {
            showError(response, 400, "Booking total is too large. Reduce quantity or days.");
            return;
        }

        int discountPercent = "FACULTY".equals(role) ? 15 : 0;
        BigDecimal discount = subtotal
                .multiply(BigDecimal.valueOf(discountPercent, 2))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal netTotal = subtotal.subtract(discount)
                .setScale(2, RoundingMode.HALF_UP);

        // 3. Save the booking and read its generated ID.
        int reservationId;
        // Pass a UTC instant to JDBC. The same instant appears on the receipt.
        LocalDateTime bookingTime = LocalDateTime.now(ZoneOffset.UTC).withNano(0);

        try (Connection connection = DBUtils.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, name);
            statement.setString(2, role);
            statement.setString(3, item);
            statement.setInt(4, quantity);
            statement.setInt(5, days);
            statement.setBigDecimal(6, dailyRate);
            statement.setBigDecimal(7, subtotal);
            statement.setBigDecimal(8, netTotal);
            statement.setTimestamp(9, java.sql.Timestamp.valueOf(bookingTime));

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("MySQL did not return a reservation ID.");
                }
                reservationId = keys.getInt(1);
            }

        } catch (SQLException e) {
            getServletContext().log("Could not save the reservation.", e);
            showError(response, 500, "Could not complete the booking. Please try again later.");
            return;
        }

        // 4. Show the receipt. Escape text before adding it to HTML.
        PrintWriter out = response.getWriter();
        out.printf("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>Booking Receipt</title>
                </head>
                <body>
                    <h1>Booking Receipt</h1>
                    <p>Reservation ID: %d</p>
                    <p>Name: %s</p>
                    <p>Role: %s</p>
                    <p>Item: %s</p>
                    <p>Quantity: %d</p>
                    <p>Booking days: %d</p>
                    <p>Daily rate: %s AED</p>
                    <p>Subtotal: %s AED</p>
                    <p>Discount: %d%% (%s AED)</p>
                    <p><strong>Net total: %s AED</strong></p>
                    <p>Timestamp (UTC): %s</p>
                    <a href="booking_form.html">Make another booking</a>
                </body>
                </html>
                """,
                reservationId, escapeHtml(name), escapeHtml(role), escapeHtml(item),
                quantity, days, dailyRate.toPlainString(), subtotal.toPlainString(),
                discountPercent, discount.toPlainString(), netTotal.toPlainString(),
                bookingTime.toString().replace('T', ' '));
    }

    private void showError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.getWriter().printf("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>Booking Error</title>
                </head>
                <body>
                    <h1>Booking Error</h1>
                    <p>%s</p>
                    <a href="booking_form.html">Back</a>
                </body>
                </html>
                """, escapeHtml(message));
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}