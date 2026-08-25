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
public record Medicion<T>(String etiqueta, T resultado, double ms) {}
