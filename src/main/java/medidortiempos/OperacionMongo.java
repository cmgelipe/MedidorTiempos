package medidortiempos;

import java.util.List;

/**
 * Una operación en la forma db.<coleccion>.<metodo>(arg1, arg2, ...)}.
 *
 * @author pipe
 */
public record OperacionMongo(String lineaOriginal, String coleccion, String metodo, List<Object> argumentos) {
}
