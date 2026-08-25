/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package medidortiempos;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.bson.Document;
import static medidortiempos.core.medir;

/**
 *
 * @author pipe
 */
public class MedidorTiempos {

    private static final int REPETICIONES = 5;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        Scanner sc = new Scanner(System.in);

        System.out.print("Ruta del archivo con las operaciones: ");
        Path archivo = Path.of(sc.nextLine().trim());

        System.out.print("Nombre de la base de datos: ");
        String nombreBd = sc.nextLine().trim();

        List<OperacionMongo> operaciones = LectorOperaciones.leer(archivo);

        Map<String, MongoClient> clientesPorHost = new HashMap<>();
        try {
            for (OperacionMongo op : operaciones) {
                String hostPuerto = op.host().contains(":") ? op.host() : op.host() + ":27017";
                MongoClient client = clientesPorHost.computeIfAbsent(hostPuerto,
                        hp -> MongoClients.create("mongodb://" + hp));
                MongoDatabase db = client.getDatabase(nombreBd);
                medirOperacion(db, op);
            }
        } finally {
            clientesPorHost.values().forEach(MongoClient::close);
        }
    }

    private static void medirOperacion(MongoDatabase db, OperacionMongo op) {
        boolean escritura = EjecutorOperaciones.esEscritura(op);
        System.out.printf("%n== [%s] %s (%s) ==%n", op.host(), op.lineaOriginal(), escritura ? "escritura, sobre clon temporal" : "lectura");

        List<Double> tiempos = new ArrayList<>();
        for (int i = 1; i <= REPETICIONES; i++) {
            MongoCollection<Document> coleccion;
            String coleccionTemp = null;
            if (escritura) {
                coleccion = EjecutorOperaciones.clonarColeccion(db, op.coleccion());
                coleccionTemp = coleccion.getNamespace().getCollectionName();
            } else {
                coleccion = db.getCollection(op.coleccion());
            }

            var medicion = medir(op.metodo() + " rep " + i, () -> EjecutorOperaciones.ejecutar(coleccion, op));

            if (coleccionTemp != null) {
                db.getCollection(coleccionTemp).drop();
            }

            System.out.printf("  rep %d: %.3f ms%n", i, medicion.ms());
            tiempos.add(medicion.ms());
        }

        double total = tiempos.stream().mapToDouble(Double::doubleValue).sum();
        double promedio = total / tiempos.size();
        System.out.printf("  tiempo total: %.3f ms%n", total);
        System.out.printf("  tiempo promedio: %.3f ms%n", promedio);
        System.out.printf("  percentil 95 (sin mejor/peor caso): %.3f ms%n", percentil95(tiempos));
    }

    /**
     * Descarta el mejor y el peor tiempo y promedia los restantes.
     */
    private static double percentil95(List<Double> tiempos) {
        double[] ordenados = tiempos.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double[] intermedios = Arrays.copyOfRange(ordenados, 1, ordenados.length - 1);
        return Arrays.stream(intermedios).average().orElseThrow();
    }

}
