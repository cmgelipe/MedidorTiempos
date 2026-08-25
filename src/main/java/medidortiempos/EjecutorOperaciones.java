package medidortiempos;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.IndexOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bson.Document;

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
