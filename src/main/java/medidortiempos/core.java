/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package medidortiempos;

import java.util.function.Supplier;

/**
 *
 * @author pipe
 */
public class core {

    public static <T> Medicion<T> medir(String etiqueta, Supplier<T> operacion) {
        long t0 = System.nanoTime();
        T resultado = operacion.get();
        long ns = System.nanoTime() - t0;
        return new Medicion<>(etiqueta, resultado, ns / 1_000_000.0);
    }

}
