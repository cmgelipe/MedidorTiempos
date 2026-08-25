package medidortiempos;

/**
 * Convierte una expresión estilo mongosh (claves sin comillas, strings con
 * comilla simple) a JSON estricto, para poder parsearla con {@link org.bson.Document#parse}.
 *
 * @author pipe
 */
final class JsonRelajado {

    private JsonRelajado() {
    }

    static String normalizar(String origen) {
        StringBuilder salida = new StringBuilder();
        int i = 0;
        int n = origen.length();
        while (i < n) {
            char c = origen.charAt(i);
            if (c == '\'' || c == '"') {
                i = copiarString(origen, i, salida);
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int inicio = i;
                while (i < n && (Character.isLetterOrDigit(origen.charAt(i)) || origen.charAt(i) == '_' || origen.charAt(i) == '$')) {
                    i++;
                }
                String identificador = origen.substring(inicio, i);
                int j = i;
                while (j < n && Character.isWhitespace(origen.charAt(j))) {
                    j++;
                }
                if (j < n && origen.charAt(j) == ':') {
                    salida.append('"').append(identificador).append('"');
                } else {
                    salida.append(identificador);
                }
            } else {
                salida.append(c);
                i++;
            }
        }
        return salida.toString();
    }

    /**
     * Copia el literal de string que comienza en {@code origen[i]}, convirtiendo
     * comillas simples a dobles cuando aplica. Devuelve el índice siguiente al cierre.
     */
    private static int copiarString(String origen, int i, StringBuilder salida) {
        char comilla = origen.charAt(i);
        int n = origen.length();
        salida.append('"');
        i++;
        while (i < n && origen.charAt(i) != comilla) {
            char c = origen.charAt(i);
            if (c == '\\' && i + 1 < n) {
                salida.append(c).append(origen.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                salida.append('\\').append('"');
            } else {
                salida.append(c);
            }
            i++;
        }
        salida.append('"');
        return i + 1;
    }

    /**
     * Devuelve el índice del paréntesis/corchete/llave que cierra el que abre en {@code apertura}.
     */
    static int cierreCoincidente(String s, int apertura) {
        char abre = s.charAt(apertura);
        char cierra = switch (abre) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            default -> throw new IllegalArgumentException("No es un carácter de apertura: " + abre);
        };
        int profundidad = 0;
        int i = apertura;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = saltarString(s, i);
                continue;
            }
            if (c == abre) {
                profundidad++;
            } else if (c == cierra) {
                profundidad--;
                if (profundidad == 0) {
                    return i;
                }
            }
            i++;
        }
        throw new IllegalArgumentException("No se encontró el cierre para: " + s.substring(apertura));
    }

    private static int saltarString(String s, int i) {
        char comilla = s.charAt(i);
        int n = s.length();
        i++;
        while (i < n && s.charAt(i) != comilla) {
            if (s.charAt(i) == '\\' && i + 1 < n) {
                i += 2;
            } else {
                i++;
            }
        }
        return i + 1;
    }

    /**
     * Divide {@code args} en sus argumentos de nivel superior, respetando strings y anidamiento.
     */
    static java.util.List<String> dividirArgumentos(String args) {
        java.util.List<String> partes = new java.util.ArrayList<>();
        int n = args.length();
        int i = 0;
        int inicio = 0;
        int profundidad = 0;
        while (i < n) {
            char c = args.charAt(i);
            if (c == '\'' || c == '"') {
                i = saltarString(args, i);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                profundidad++;
            } else if (c == ')' || c == ']' || c == '}') {
                profundidad--;
            } else if (c == ',' && profundidad == 0) {
                partes.add(args.substring(inicio, i).trim());
                inicio = i + 1;
            }
            i++;
        }
        String resto = args.substring(inicio).trim();
        if (!resto.isEmpty()) {
            partes.add(resto);
        }
        return partes;
    }
}
