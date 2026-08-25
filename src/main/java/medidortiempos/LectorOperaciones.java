package medidortiempos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;

/**
 * Lee un archivo de texto plano con operaciones escritas como en mongosh
 * (una por línea), por ejemplo: {@code db.empleados.find({nombre:'felipe'})}.
 *
 * @author pipe
 */
final class LectorOperaciones {

    private LectorOperaciones() {
    }

    static List<OperacionMongo> leer(Path archivo) throws IOException {
        List<OperacionMongo> operaciones = new ArrayList<>();
        List<String> lineas = Files.readAllLines(archivo);
        for (String linea : lineas) {
            String limpia = linea.trim();
            if (limpia.isEmpty() || limpia.startsWith("//") || limpia.startsWith("#")) {
                continue;
            }
            operaciones.add(parsear(limpia));
        }
        return operaciones;
    }

    private static OperacionMongo parsear(String linea) {
        String cuerpo = linea.endsWith(";") ? linea.substring(0, linea.length() - 1).trim() : linea;
        if (!cuerpo.startsWith("db.")) {
            throw new IllegalArgumentException("Operación inválida, se esperaba 'db.<coleccion>.<metodo>(...)': " + linea);
        }
        int puntoColeccion = cuerpo.indexOf('.', 3);
        if (puntoColeccion < 0) {
            throw new IllegalArgumentException("No se pudo determinar la colección en: " + linea);
        }
        String coleccion = cuerpo.substring(3, puntoColeccion);

        int parentesis = cuerpo.indexOf('(', puntoColeccion);
        if (parentesis < 0) {
            throw new IllegalArgumentException("No se pudo determinar el método en: " + linea);
        }
        String metodo = cuerpo.substring(puntoColeccion + 1, parentesis).trim();

        int cierre = JsonRelajado.cierreCoincidente(cuerpo, parentesis);
        String argsCrudos = cuerpo.substring(parentesis + 1, cierre).trim();

        List<Object> argumentos = new ArrayList<>();
        if (!argsCrudos.isEmpty()) {
            for (String argCrudo : JsonRelajado.dividirArgumentos(argsCrudos)) {
                argumentos.add(parsearArgumento(argCrudo));
            }
        }
        return new OperacionMongo(linea, coleccion, metodo, argumentos);
    }

    private static Object parsearArgumento(String argCrudo) {
        String json = JsonRelajado.normalizar(argCrudo);
        Document envoltura = Document.parse("{\"_\":" + json + "}");
        return envoltura.get("_");
    }
}
