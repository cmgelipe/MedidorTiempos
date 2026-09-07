package medidortiempos;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * Ejecuta una {@link OperacionMongo}. Las operaciones de solo lectura corren
 * directo contra la colección real. Las de escritura corren contra un clon
 * temporal (datos + índices) que se destruye justo después de medir, para que
 * la base de datos real nunca quede modificada.
 *
 * @author pipe
 */
final class EjecutorOperaciones {

    private static final Set<String> METODOS_ESCRITURA = Set.of(
            "insertOne", "insertMany",
            "updateOne", "updateMany", "replaceOne",
            "deleteOne", "deleteMany",
            "findOneAndUpdate", "findOneAndDelete", "findOneAndReplace");

    private EjecutorOperaciones() {
    }

    static boolean esEscritura(OperacionMongo op) {
        if (METODOS_ESCRITURA.contains(op.metodo())) {
            return true;
        }
        if ("aggregate".equals(op.metodo()) && !op.argumentos().isEmpty()) {
            for (Document etapa : pipeline(op.argumentos().get(0))) {
                if (etapa.containsKey("$out") || etapa.containsKey("$merge")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Clona colección (documentos + índices) a una colección temporal descartable.
     */
    static MongoCollection<Document> clonarColeccion(MongoDatabase db, String coleccionOriginal) {
        String nombreTemp = "_medidor_tmp_" + coleccionOriginal;
        MongoCollection<Document> temp = db.getCollection(nombreTemp);
        temp.drop();

        MongoCollection<Document> original = db.getCollection(coleccionOriginal);
        original.aggregate(List.of(Aggregates.out(nombreTemp))).toCollection();

        for (Document indice : original.listIndexes()) {
            String nombreIndice = indice.getString("name");
            if ("_id_".equals(nombreIndice)) {
                continue;
            }
            Document claves = indice.get("key", Document.class);
            IndexOptions opciones = new IndexOptions().name(nombreIndice);
            if (Boolean.TRUE.equals(indice.getBoolean("unique"))) {
                opciones.unique(true);
            }
            temp.createIndex(claves, opciones);
        }
        return temp;
    }

    /**
     * Campos con índice único de la colección (incluye siempre "_id").
     * Solo considera índices de un solo campo; los compuestos se ignoran
     * porque la colisión dependería de la combinación de varios valores.
     */
    private static Set<String> camposUnicos(MongoCollection<Document> original) {
        Set<String> campos = new HashSet<>();
        campos.add("_id");
        for (Document indice : original.listIndexes()) {
            if (!Boolean.TRUE.equals(indice.getBoolean("unique"))) {
                continue;
            }
            Document claves = indice.get("key", Document.class);
            if (claves.size() == 1) {
                campos.add(claves.keySet().iterator().next());
            }
        }
        return campos;
    }

    /**
     * Evita que insert/update/replace fallen por clave duplicada en el clon
     * temporal: si el valor que se va a escribir en un campo único ya existe
     * en otro documento del clon, ese documento se elimina del clon (nunca de
     * la colección real) antes de medir. Sobre "_id" no se aplica en updates
     * porque MongoDB no permite modificarlo (campo inmutable).
     */
    static void eliminarColisionesDeClavesUnicas(MongoCollection<Document> coll, MongoCollection<Document> original, OperacionMongo op) {
        Set<String> camposUnicos = camposUnicos(original);
        List<Object> a = op.argumentos();
        switch (op.metodo()) {
            case "insertOne" ->
                eliminarColisionesInsert(coll, camposUnicos, documento(a, 0));
            case "insertMany" -> {
                for (Document doc : documentos(a.get(0))) {
                    eliminarColisionesInsert(coll, camposUnicos, doc);
                }
            }
            case "updateOne", "findOneAndUpdate" ->
                eliminarColisionesUpdate(coll, camposUnicos, filtro(a, 0), documento(a, 1).get("$set", Document.class));
            case "replaceOne", "findOneAndReplace" ->
                eliminarColisionesUpdate(coll, camposUnicos, filtro(a, 0), documento(a, 1));
            default -> {
                // updateMany/deleteOne/deleteMany no fijan un valor único literal a un solo documento
            }
        }
    }

    private static void eliminarColisionesInsert(MongoCollection<Document> coll, Set<String> camposUnicos, Document doc) {
        for (String campo : camposUnicos) {
            Object valor = doc.get(campo);
            if (valor != null) {
                coll.deleteMany(Filters.eq(campo, valor));
            }
        }
    }

    private static void eliminarColisionesUpdate(MongoCollection<Document> coll, Set<String> camposUnicos, Document filtroOriginal, Document nuevosValores) {
        if (nuevosValores == null || nuevosValores.isEmpty()) {
            return;
        }
        List<Object> idsObjetivo = new ArrayList<>();
        for (Document doc : coll.find(filtroOriginal)) {
            idsObjetivo.add(doc.get("_id"));
        }
        for (String campo : camposUnicos) {
            if (campo.equals("_id") || !nuevosValores.containsKey(campo)) {
                continue;
            }
            Bson colision = Filters.and(Filters.eq(campo, nuevosValores.get(campo)), Filters.nin("_id", idsObjetivo));
            coll.deleteMany(colision);
        }
    }

    static Object ejecutar(MongoCollection<Document> coll, OperacionMongo op) {
        List<Object> a = op.argumentos();
        return switch (op.metodo()) {
            case "find" ->
                coll.find(filtro(a, 0)).into(new ArrayList<>());
            case "findOne" ->
                coll.find(filtro(a, 0)).first();
            case "countDocuments" ->
                coll.countDocuments(filtro(a, 0));
            case "distinct" ->
                coll.distinct((String) a.get(0), filtro(a, 1), Object.class).into(new ArrayList<>());
            case "aggregate" ->
                coll.aggregate(pipeline(a.get(0))).into(new ArrayList<>());
            case "insertOne" ->
                coll.insertOne(documento(a, 0));
            case "insertMany" ->
                coll.insertMany(documentos(a.get(0)));
            case "updateOne" ->
                coll.updateOne(filtro(a, 0), documento(a, 1));
            case "updateMany" ->
                coll.updateMany(filtro(a, 0), documento(a, 1));
            case "replaceOne" ->
                coll.replaceOne(filtro(a, 0), documento(a, 1));
            case "deleteOne" ->
                coll.deleteOne(filtro(a, 0));
            case "deleteMany" ->
                coll.deleteMany(filtro(a, 0));
            case "findOneAndUpdate" ->
                coll.findOneAndUpdate(filtro(a, 0), documento(a, 1));
            case "findOneAndDelete" ->
                coll.findOneAndDelete(filtro(a, 0));
            case "findOneAndReplace" ->
                coll.findOneAndReplace(filtro(a, 0), documento(a, 1));
            default ->
                throw new UnsupportedOperationException("Método no soportado: " + op.metodo());
        };
    }

    private static Document filtro(List<Object> args, int indice) {
        if (indice >= args.size()) {
            return new Document();
        }
        return (Document) args.get(indice);
    }

    private static Document documento(List<Object> args, int indice) {
        return (Document) args.get(indice);
    }

    @SuppressWarnings("unchecked")
    private static List<Document> documentos(Object arg) {
        return (List<Document>) (List<?>) arg;
    }

    @SuppressWarnings("unchecked")
    private static List<Document> pipeline(Object arg) {
        return (List<Document>) (List<?>) arg;
    }
}
