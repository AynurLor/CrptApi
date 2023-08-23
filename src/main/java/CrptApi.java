import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.http.HttpHeaders;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {
    private final int requestLimit;
    private final TimeUnit timeUnit;
    private final Lock lock = new ReentrantLock();
    private long lastRequestTime = System.currentTimeMillis();
    private int requestsInInterval = 0;
    private final Semaphore requestSemaphore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.requestSemaphore = new Semaphore(requestLimit);
    }

    public void productEntry(Document document, String signature) {
        lock.lock();
        try {
            long currentTime = System.currentTimeMillis();
            long intervalInMillis = timeUnit.toMillis(1);

            if (currentTime - lastRequestTime >= intervalInMillis) {
                lastRequestTime = currentTime;
                requestsInInterval = 0;
            }

            if (requestsInInterval < requestLimit && requestSemaphore.tryAcquire()) {
                try {
                    System.out.println(apiRequest(document, signature));
                } catch (IOException | InterruptedException e) {
                    System.err.println(e.getMessage());
                }
                System.out.println("Document created: " + document);
                requestsInInterval++;
            } else {
                System.out.println("Request limit exceeded. Please wait.");
            }
        } finally {
            lock.unlock();
        }
    }

    @Data
    @AllArgsConstructor
    public class Document {

        private Description description;
        private String doc_id;
        private String doc_status;
        private String doc_type;
        private boolean importRequest;
        private String owner_inn;
        private String producer_inn;
        private Date production_date;
        private String production_type;
        private Product products_list;
        private Date reg_date;
        private String reg_number;

    }
    @Data
    @AllArgsConstructor
    public class Product {
        private Date certificate_doc_date;
        private String certificate_doc_number;
        private String certificate_doc_type;
        private String producerName;
        private Date production_date;
        private String tnved_code;
        private String uit_code;
        private String uitu_code;
        }
    @Data
    @AllArgsConstructor
    public class Description {
        private String participantInn;
    }

    public Document newDocument(String doc_id, String doc_status,
                                String doc_type, boolean importRequest, String participant_inn,
                                String producer_inn, Date production_date, String production_type,
                                Date certificate_doc_date, String certificate_doc_number,
                                String certificate_doc_type, String producerName,
                                Date production_list_date, String tnved_code, String uit_code,
                                String uitu_code, Date reg_date, String reg_number) {

        Description description = this.new Description(participant_inn);
        Product product = this.new Product(certificate_doc_date, certificate_doc_number,
                certificate_doc_type, producerName, production_list_date, tnved_code,
                uit_code, uitu_code);

        return this.new Document(description, doc_id, doc_status, doc_type, importRequest,
                participant_inn, producer_inn, production_date, production_type, product,
                reg_date,reg_number);
    }

    private String getUuid() throws IOException, InterruptedException {
        String authKeyURL = "https://ismp.crpt.ru/api/v3/auth/cert/key";
        var request_uuid = HttpRequest.newBuilder().uri(URI.create(authKeyURL)).GET().build();
        return client.send(request_uuid, HttpResponse.BodyHandlers.ofString()).body().split("\".\"")[1];
    }

    private String getToken(String uuid, String signature) throws IOException, InterruptedException {

        String authTokenURL = "https://ismp.crpt.ru/api/v3/auth/cert/";
        var apiUrl = URI.create(authTokenURL);

        String jsonData = "{\n" +
                "  \"uuid\": \""+ uuid +"\",\n" +
                "  \"data\": \""+ signature+ "\"\n" +
                "}";

        var request = HttpRequest.newBuilder().uri(apiUrl)
                .header("content-type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonData)).build();

        var token_request = client.send(request, HttpResponse.BodyHandlers.ofString()).body();

        if (token_request.startsWith("400", 8)) {
            throw new IllegalArgumentException("Уникальная подпись идентификатора uuid некорректна, страница недоступна");
        }

        return token_request.split("\".\"")[1];
    }

    public String apiRequest(Document document, String signature) throws IOException, InterruptedException {
        var uuid = getUuid();
        var token = getToken(uuid, signature);

        var apiUrl = URI.create("https://ismp.crpt.ru/api/v2/rollout");
        var json = objectMapper.writeValueAsString(document);

        var request = HttpRequest.newBuilder().uri(apiUrl)
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8")
                .header("clientToken", token)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();

        String response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();

        requestSemaphore.release();

        return response;
    }
}
