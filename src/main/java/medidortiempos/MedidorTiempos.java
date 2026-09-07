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

        System.out.print("Sitios/IPs a recorrer (separados por coma): ");
        List<String> sitios = Arrays.stream(sc.nextLine().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(MedidorTiempos::normalizarHost)
                .toList();

        List<OperacionMongo> operaciones = LectorOperaciones.leer(archivo);

        Map<String, MongoClient> clientesPorHost = new HashMap<>();
        try {
            for (OperacionMongo op : operaciones) {
                medirOperacionFragmentada(clientesPorHost, sitios, nombreBd, op);
            }
        } finally {
            clientesPorHost.values().forEach(MongoClient::close);
        }
    }

    private static String normalizarHost(String host) {
        return host.contains(":") ? host : host + ":27017";
    }

    /**
     * Descubre en qué sitios y en qué colecciones-fragmento (mismo prefijo que
     * op.coleccion(), distinto sufijo numérico) hay datos que le corresponden
     * a esta operación, mide la operación en cada uno de esos fragmentos, y
     * combina los tiempos de todos ellos en un solo reporte.
     */
    private static void medirOperacionFragmentada(Map<String, MongoClient> clientes, List<String> sitios, String nombreBd, OperacionMongo op) {
        boolean escritura = EjecutorOperaciones.esEscritura(op);
        System.out.printf("%n== [origen %s] %s (%s) ==%n", op.host(), op.lineaOriginal(), escritura ? "escritura" : "lectura");

        Document predicado = EjecutorOperaciones.predicadoDescubrimiento(op);
        List<Double> tiemposCombinados = new ArrayList<>();
        int fragmentosEncontrados = 0;

        if (predicado == null) {
            // No se pudo construir un predicado de descubrimiento (ej. insertOne sin _id):
            // se ejecuta únicamente en el sitio/colección literal que trae la línea.
            System.out.println("  (sin _id para descubrir fragmentos; se usa solo el sitio/colección de la línea)");
            String sitio = normalizarHost(op.host());
            MongoDatabase db = obtenerDb(clientes, sitio, nombreBd);
            List<Double> tiempos = medirEnFragmento(db, op.coleccion(), op, escritura);
            if (!tiempos.isEmpty()) {
                fragmentosEncontrados = 1;
                tiemposCombinados.addAll(tiempos);
            }
        } else {
            String prefijo = EjecutorOperaciones.prefijoFragmento(op.coleccion());
            for (String sitio : sitios) {
                MongoDatabase db = obtenerDb(clientes, sitio, nombreBd);
                for (String fragmento : EjecutorOperaciones.coleccionesConPrefijo(db, prefijo)) {
                    long coincidencias;
                    try {
                        coincidencias = db.getCollection(fragmento).countDocuments(predicado);
                    } catch (RuntimeException e) {
                        System.out.printf("  [%s].%s: no se pudo consultar (%s)%n", sitio, fragmento, e.getMessage());
                        continue;
                    }
                    if (coincidencias == 0) {
                        continue;
                    }
                    System.out.printf("  -> encontrado en [%s].%s (%d coincidencia(s))%n", sitio, fragmento, coincidencias);
                    fragmentosEncontrados++;
                    tiemposCombinados.addAll(medirEnFragmento(db, fragmento, op, escritura));
                }
            }
        }

        if (tiemposCombinados.isEmpty()) {
            System.out.println("  no se encontraron datos de esta operación en ningún sitio/fragmento.");
            return;
        }

        double total = tiemposCombinados.stream().mapToDouble(Double::doubleValue).sum();
        double promedio = total / tiemposCombinados.size();
        System.out.printf("  fragmentos que respondieron: %d (%d mediciones combinadas)%n", fragmentosEncontrados, tiemposCombinados.size());
        System.out.printf("  tiempo total: %.3f ms%n", total);
        System.out.printf("  tiempo promedio: %.3f ms%n", promedio);
        if (tiemposCombinados.size() >= 3) {
            System.out.printf("  percentil 95 (sin mejor/peor caso): %.3f ms%n", percentil95(tiemposCombinados));
        }
    }

    private static MongoDatabase obtenerDb(Map<String, MongoClient> clientes, String sitio, String nombreBd) {
        MongoClient client = clientes.computeIfAbsent(sitio, s -> MongoClients.create("mongodb://" + s));
        return client.getDatabase(nombreBd);
    }

    /**
     * Ejecuta las {@value #REPETICIONES} repeticiones de la operación contra un
     * fragmento (colección) concreto de un sitio concreto, e imprime cada tiempo.
     */
    private static List<Double> medirEnFragmento(MongoDatabase db, String fragmento, OperacionMongo op, boolean escritura) {
        List<Double> tiempos = new ArrayList<>();
        for (int i = 1; i <= REPETICIONES; i++) {
            MongoCollection<Document> coleccion = null;
            String coleccionTemp = null;
            try {
                if (escritura) {
                    coleccion = EjecutorOperaciones.clonarColeccion(db, fragmento);
                    coleccionTemp = coleccion.getNamespace().getCollectionName();
                    EjecutorOperaciones.eliminarColisionesDeClavesUnicas(coleccion, db.getCollection(fragmento), op);
                } else {
                    coleccion = db.getCollection(fragmento);
                }

                MongoCollection<Document> coleccionFinal = coleccion;
                var medicion = medir(op.metodo() + " rep " + i, () -> EjecutorOperaciones.ejecutar(coleccionFinal, op));

                System.out.printf("    rep %d: %.3f ms%n", i, medicion.ms());
                tiempos.add(medicion.ms());
            } catch (RuntimeException e) {
                System.out.printf("    rep %d: ERROR - %s%n", i, e.getMessage());
                break;
            } finally {
                if (coleccionTemp != null) {
                    db.getCollection(coleccionTemp).drop();
                }
            }
        }
        return tiempos;
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
