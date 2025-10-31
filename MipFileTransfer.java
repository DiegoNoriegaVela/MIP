import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.Calendar;

/**
 * MipFileTransfer - Sistema de Transferencia de Archivos IPM hacia/desde MIP de Mastercard
 * 
 * Implementacion completa del protocolo de transferencia bulk de Mastercard segun
 * "File Transfer Manual Version 1.1" de Mastercard (22 June 2020).
 * 
 * Este sistema maneja dos tipos de operaciones:
 * 
 * 1. ENVIO (TO MASTERCARD):
 *    - Codigo 004 Header + Bloques de datos 'R' + Trailer 998
 *    - Transmission ID formato: RtttEEEEEJJJSS (14 chars)
 *    - Direction Indicator: 'R' (Received by Mastercard)
 * 
 * 2. RECEPCION (FROM MASTERCARD):
 *    - Codigo 101 Request + Header 004 + Bloques de datos 'T' + Trailer 998 + Purge 999
 *    - Transmission ID formato: TtttEEEEEJJJSS (14 chars)
 *    - Direction Indicator: 'T' (Transmitted by Mastercard)
 *    - Busqueda automatica: Si no encuentra archivo con SS especificado, incrementa SS hasta 99
 * 
 * Caracteristicas clave:
 * - Codificacion EBCDIC (Cp500) para todos los campos alfanumericos
 * - Bloques de datos de maximo 1014 bytes
 * - Framing con 2 bytes de longitud Big-Endian
 * - Validacion de ACKs en formato 998
 * - Timeouts: 15s conexion, 20s I/O
 * 
 * Archivos IPM: Los archivos IPM se transfieren codificados en EBCDIC sin decodificacion
 * adicional. El procesamiento posterior de estos archivos es responsabilidad de otros
 * componentes del sistema.
 * 
 * Referencias:
 * - Paginas 49-54: Record Types Used in Bulk File Transfer Protocol
 * - Paginas 36-37: Successful Transfer Dialog (TO Mastercard)
 * - Paginas 44-45: Successful Transfer Dialog (FROM Mastercard)
 * - Pagina 32: Generic File Naming on the MIP
 * 
 * @author Sistema de Integracion Mastercard
 * @version 2.0
 * @since 2024
 */
public final class MipFileTransfer {

    // Configuracion de codificacion EBCDIC segun estandar Mastercard
    private static final Charset EBCDIC = Charset.forName("Cp500");
    
    // Tamanio maximo de datos por bloque segun especificacion Mastercard (pag. 50, 53)
    private static final int DATA_CHUNK = 1014;
    
    // Flag de debug para diagnostico detallado
    private static final boolean DEBUG = Boolean.getBoolean("mip.debug");

    public static void main(String[] args) {
        try {
            Params p = Params.parse(args);
            if (p == null) { 
                usage(); 
                System.exit(2); 
            }

            // Ejecutar operacion segun modo seleccionado
            if ("send".equalsIgnoreCase(p.mode)) {
                sendToMip(p);
            } else if ("receive".equalsIgnoreCase(p.mode)) {
                receiveFromMip(p);
            } else {
                System.err.println("ERROR: Modo invalido. Use 'send' o 'receive'");
                usage();
                System.exit(2);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Muestra instrucciones de uso del programa
     */
    private static void usage() {
        System.out.println(
            "Uso:\n" +
            "  ENVIO (TO Mastercard):\n" +
            "    java MipFileTransfer --mode send --ip <host> --port <puerto> \\\n" +
            "         --file <path_ipm> --ipmname <R119xxxxx[dddss]>\n" +
            "\n" +
            "  RECEPCION (FROM Mastercard):\n" +
            "    java MipFileTransfer --mode receive --ip <host> --port <puerto> \\\n" +
            "         --file <path_destino> --ipmname <T112xxxxx[dddss]>\n" +
            "\n" +
            "Parametros:\n" +
            "  --mode      : 'send' o 'receive'\n" +
            "  --ip        : Direccion IP del MIP\n" +
            "  --port      : Puerto del MIP\n" +
            "  --file      : Path del archivo IPM (origen para send, destino para receive)\n" +
            "  --ipmname   : Transmission ID (9 o 14 chars)\n" +
            "                - SEND: R+tipo3+endpoint5 o RtttEEEEEJJJSS\n" +
            "                - RECEIVE: T+tipo3+endpoint5 o TtttEEEEEJJJSS\n" +
            "\n" +
            "Ejemplos:\n" +
            "  java MipFileTransfer --mode send --ip 10.0.0.1 --port 5000 \\\n" +
            "       --file /data/ipm_out.dat --ipmname R11902840\n" +
            "\n" +
            "  java MipFileTransfer --mode receive --ip 10.0.0.1 --port 5000 \\\n" +
            "       --file /data/ipm_in.dat --ipmname T11200157\n" +
            "\n" +
            "Debug:\n" +
            "  Para diagnostico detallado, ejecutar con:\n" +
            "  java -Dmip.debug=true MipFileTransfer ...");
    }

    /**
     * OPERACION SEND: Envia archivo IPM hacia MIP (TO Mastercard)
     * 
     * Implementa el protocolo documentado en paginas 36-37, 49-50 del manual:
     * 1. Envia Header 004 "To Mastercard"
     * 2. Envia bloques de datos con Direction Indicator 'R'
     * 3. Envia Trailer 998 con conteo de bloques
     * 
     * Nota: El Purge 999 se ha eliminado del flujo TO Mastercard para mayor
     * compatibilidad, ya que no esta documentado en el dialogo de ejemplo
     * de la pagina 36-37 del manual.
     * 
     * @param p Parametros de conexion y archivo
     * @throws Exception Si ocurre error de I/O o protocolo
     */
    private static void sendToMip(Params p) throws Exception {
        File f = new File(p.filePath);
        if (!f.exists() || !f.isFile()) {
            throw new FileNotFoundException("No existe el archivo: " + f.getAbsolutePath());
        }

        // Normalizar Transmission ID a 14 caracteres
        String txId = normalizeTransmissionId(p.ipmName, 'R');
        
        System.out.println("==============================================");
        System.out.println("INICIA PROCESO DE ENVIO (TO MASTERCARD)");
        System.out.println("==============================================");
        System.out.println("Archivo IPM    : " + f.getAbsolutePath());
        System.out.println("Tamano         : " + f.length() + " bytes");
        System.out.println("Transmission ID: " + txId);
        System.out.println("MIP Host       : " + p.ip + ":" + p.port);
        System.out.println("==============================================");

        // Establecer conexion TCP con el MIP
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(p.ip, p.port), 15000);
        sock.setSoTimeout(20000);

        InputStream in = sock.getInputStream();
        OutputStream out = sock.getOutputStream();

        try {
            // PASO 1: Enviar Header 004 "To Mastercard" (pag. 49)
            System.out.println("\n[1/3] Enviando Header 004...");
            byte[] hdr004 = buildHeader004(txId);
            writeFramed(out, hdr004);

            Frame r = readFramed(in);
            checkAck("Header 004", r);

            // PASO 2: Enviar bloques de datos con Direction Indicator 'R' (pag. 50)
            System.out.println("\n[2/3] Enviando bloques de datos...");
            int blocks = 0;
            FileInputStream fis = new FileInputStream(f);
            try {
                byte[] buf = new byte[DATA_CHUNK];
                int n;
                while ((n = fis.read(buf)) >= 0) {
                    if (n == 0) break;
                    
                    // Construir registro de datos: 'R' + payload
                    ByteArrayOutputStream rec = new ByteArrayOutputStream(1 + n);
                    rec.write(ebcdicByte('R'));  // Direction Indicator: TO Mastercard
                    rec.write(buf, 0, n);         // Datos del archivo IPM
                    writeFramed(out, rec.toByteArray());
                    blocks++;
                    
                    if (blocks % 10 == 0) {
                        System.out.println("  Bloques enviados: " + blocks);
                    }
                }
            } finally { 
                try { fis.close(); } catch (Exception ignore) {} 
            }
            System.out.println("  Total bloques de datos: " + blocks);

            // PASO 3: Enviar Trailer 998 (pag. 50)
            System.out.println("\n[3/3] Enviando Trailer 998...");
            byte[] tr998 = buildTrailer998(blocks);
            writeFramed(out, tr998);

            r = readFramed(in);
            checkAck("Trailer 998", r);

            System.out.println("\n==============================================");
            System.out.println("ENVIO COMPLETADO EXITOSAMENTE");
            System.out.println("Bloques de datos : " + blocks);
            System.out.println("Bloques totales  : " + (blocks + 1) + " (incluye trailer)");
            System.out.println("==============================================");
            
        } finally {
            try { sock.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * OPERACION RECEIVE: Recibe archivo IPM desde MIP (FROM Mastercard)
     * 
     * Implementa el protocolo documentado en paginas 44-45, 51-53 del manual:
     * 1. Envia Request 101 "From Mastercard"
     * 2. Recibe Header 004 con informacion del archivo
     * 3. Recibe bloques de datos con Direction Indicator 'T'
     * 4. Recibe Trailer 998 con conteo de bloques
     * 5. Envia Purge 999 para marcar archivo como eliminable
     * 
     * BUSQUEDA AUTOMATICA:
     * Si el archivo solicitado no existe (con el SS especificado), el sistema
     * incrementara automaticamente el numero de secuencia (SS) desde el valor
     * inicial hasta 99, buscando el primer archivo disponible.
     * 
     * Formato Transmission ID: TtttEEEEEJJJSS
     * - SS puede ir de 01 a 99
     * 
     * El archivo se guarda codificado en EBCDIC tal como se recibe del MIP.
     * 
     * @param p Parametros de conexion y archivo
     * @throws Exception Si ocurre error de I/O o protocolo
     */
    private static void receiveFromMip(Params p) throws Exception {
        // Normalizar Transmission ID a 14 caracteres
        String txId = normalizeTransmissionId(p.ipmName, 'T');
        
        System.out.println("==============================================");
        System.out.println("INICIA PROCESO DE RECEPCION (FROM MASTERCARD)");
        System.out.println("==============================================");
        System.out.println("Archivo destino: " + p.filePath);
        System.out.println("Transmission ID: " + txId);
        System.out.println("MIP Host       : " + p.ip + ":" + p.port);
        if (DEBUG) {
            System.out.println("Modo DEBUG     : ACTIVADO");
        }
        System.out.println("==============================================");

        // Extraer el numero de secuencia actual del Transmission ID
        int currentSeq = Integer.parseInt(txId.substring(12, 14));
        
        // Variables para control de busqueda
        boolean found = false;
        String lastError = null;
        String successfulTxId = null;
        
        // Intentar desde currentSeq hasta 99
        for (int seq = currentSeq; seq <= 99 && !found; seq++) {
            String tryTxId = txId.substring(0, 12) + String.format("%02d", seq);
            
            if (seq > currentSeq) {
                System.out.println("\n[BUSQUEDA] Intentando con secuencia " + seq + ": " + tryTxId);
            }
            
            Socket sock = null;
            try {
                // Establecer conexion TCP con el MIP
                sock = new Socket();
                sock.connect(new InetSocketAddress(p.ip, p.port), 15000);
                sock.setSoTimeout(20000);

                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();

                try {
                    // PASO 1: Enviar Request 101 "From Mastercard" (pag. 51)
                    if (seq == currentSeq) {
                        System.out.println("\n[1/5] Enviando Request 101...");
                    }
                    byte[] req101 = buildRequest101(tryTxId);
                    writeFramed(out, req101);

                    // PASO 2: Recibir Header 004 con informacion del archivo (pag. 52)
                    if (seq == currentSeq) {
                        System.out.println("\n[2/5] Recibiendo Header 004...");
                    }
                    Frame hdr = readFramed(in);
                    
                    // Validar que sea Header 004
                    String code = hdr.asEbcdic(0, 3);
                    
                    // Si recibimos un mensaje de error (998 con rc != 00)
                    if ("998".equals(code)) {
                        String rc = hdr.asEbcdic(5, 2);
                        if (!"00".equals(rc)) {
                            // Error - archivo no encontrado o no disponible
                            String errorMsg = "Archivo no disponible (rc=" + rc + ")";
                            if (hdr.data.length > 7) {
                                try {
                                    String detail = hdr.asEbcdic(7, Math.min(80, hdr.data.length - 7)).trim();
                                    if (!detail.isEmpty()) {
                                        errorMsg += " - " + detail;
                                    }
                                } catch (Exception ignore) {}
                            }
                            lastError = errorMsg;
                            
                            if (seq == currentSeq) {
                                System.out.println("  Archivo no encontrado con secuencia " + seq);
                            }
                            continue; // Intentar con siguiente secuencia
                        }
                    }
                    
                    if (!"004".equals(code)) {
                        lastError = "Respuesta inesperada del MIP: " + code + " (hex: " + hdr.hex() + ")";
                        continue;
                    }
                    
                    // Archivo encontrado
                    found = true;
                    successfulTxId = tryTxId;
                    
                    if (seq > currentSeq) {
                        System.out.println("  ENCONTRADO con secuencia " + seq);
                        System.out.println("\n[2/5] Header 004 recibido");
                    }
                    
                    // Extraer informacion del header
                    String rxTxId = hdr.asEbcdic(5, 14);
                    int expectedBlocks = hdr.asInt(36, 4);
                    
                    System.out.println("  Archivo      : " + rxTxId);
                    System.out.println("  Bloques      : " + expectedBlocks);

                    // PASO 3: Recibir bloques de datos con Direction Indicator 'T' (pag. 53)
                    System.out.println("\n[3/5] Recibiendo bloques de datos...");
                    FileOutputStream fos = new FileOutputStream(p.filePath);
                    int blocksReceived = 0;
                    
                    try {
                        while (true) {
                            Frame dataFrame = readFramed(in);
                            
                            if (DEBUG) {
                                System.out.println("  DEBUG: Frame recibido, size=" + dataFrame.data.length);
                                System.out.println("  DEBUG: Primeros bytes (hex): " + 
                                    hexBytes(dataFrame.data, 0, Math.min(16, dataFrame.data.length)));
                            }
                            
                            // Verificar si es un bloque de datos o el trailer
                            String firstBytes = dataFrame.asEbcdic(0, 3);
                            if ("998".equals(firstBytes)) {
                                // Es el trailer, salir del loop
                                System.out.println("  Total bloques recibidos: " + blocksReceived);
                                
                                // Validar el trailer
                                String type = dataFrame.asEbcdic(3, 2);
                                String rc = dataFrame.asEbcdic(5, 2);
                                
                                if (!"00".equals(rc)) {
                                    throw new IOException("Trailer con error rc=" + rc);
                                }
                                
                                // Validar conteo de bloques
                                int trailerCount = dataFrame.asInt(7, 4);
                                int expectedCount = blocksReceived + 1; // bloques + trailer
                                
                                if (trailerCount != expectedCount) {
                                    System.err.println("ADVERTENCIA: Discrepancia en conteo");
                                    System.err.println("  Esperado: " + expectedCount);
                                    System.err.println("  Recibido: " + trailerCount);
                                }
                                
                                System.out.println("  Trailer 998 OK - Count: " + trailerCount);
                                break;
                            }
                            
                            // Es un bloque de datos
                            // Analizar estructura para detectar RDW adicional o padding
                            int dataStart = 0;
                            
                            // DETECCION INTELIGENTE DE ESTRUCTURA:
                            // Caso 1: RDW de 4 bytes + Direction Indicator
                            // Caso 2: Solo Direction Indicator
                            // Caso 3: Padding (0xFF) + Direction Indicator
                            
                            if (dataFrame.data.length >= 5) {
                                // Intentar detectar RDW (4 bytes Big-Endian con longitud razonable)
                                int possibleRdw = ((dataFrame.data[0] & 0xFF) << 24) |
                                                 ((dataFrame.data[1] & 0xFF) << 16) |
                                                 ((dataFrame.data[2] & 0xFF) << 8) |
                                                 (dataFrame.data[3] & 0xFF);
                                
                                // Si es un RDW valido (positivo y menor que frame size)
                                if (possibleRdw > 0 && possibleRdw < dataFrame.data.length - 4) {
                                    dataStart = 4;
                                    if (DEBUG) {
                                        System.out.println("  DEBUG: Detectado RDW de " + possibleRdw + " bytes, ajustando offset");
                                    }
                                }
                            }
                            
                            // Verificar Direction Indicator en posicion ajustada
                            int dirIndicatorUnsigned = dataFrame.data[dataStart] & 0xFF;
                            
                            if (DEBUG) {
                                System.out.println("  DEBUG: Direction Indicator en pos " + dataStart + ": 0x" + 
                                    String.format("%02X", dirIndicatorUnsigned));
                            }
                            
                            // 0xE3 es 'T' en EBCDIC
                            if (dirIndicatorUnsigned != 0xE3) {
                                // Intentar detectar si hay padding 0xFF antes del Direction Indicator
                                if (dirIndicatorUnsigned == 0xFF && dataStart + 1 < dataFrame.data.length) {
                                    int nextByte = dataFrame.data[dataStart + 1] & 0xFF;
                                    if (nextByte == 0xE3) {
                                        // Encontrado: 0xFF seguido de 0xE3 ('T')
                                        dataStart++; // Saltar el 0xFF
                                        dirIndicatorUnsigned = nextByte;
                                        if (DEBUG) {
                                            System.out.println("  DEBUG: Detectado padding 0xFF, ajustando a pos " + dataStart);
                                        }
                                    }
                                }
                                
                                // Si aun no es 0xE3, mostrar advertencia
                                if (dirIndicatorUnsigned != 0xE3) {
                                    System.err.println("ADVERTENCIA: Direction Indicator esperado 'T' (0xE3), recibi: 0x" 
                                        + String.format("%02X", dirIndicatorUnsigned));
                                    
                                    if (DEBUG) {
                                        System.err.println("  DEBUG: Contexto (20 bytes): " + 
                                            hexBytes(dataFrame.data, 0, Math.min(20, dataFrame.data.length)));
                                    }
                                }
                            }
                            
                            // Escribir datos al archivo (desde dataStart + 1, saltando Direction Indicator)
                            int payloadStart = dataStart + 1;
                            int payloadLength = dataFrame.data.length - payloadStart;
                            
                            if (payloadLength > 0) {
                                fos.write(dataFrame.data, payloadStart, payloadLength);
                            }
                            
                            blocksReceived++;
                            
                            if (blocksReceived % 10 == 0) {
                                System.out.println("  Bloques recibidos: " + blocksReceived);
                            }
                        }
                    } finally {
                        try { fos.close(); } catch (Exception ignore) {}
                    }

                    // PASO 4: Enviar Purge 999 para marcar archivo como eliminable (pag. 54)
                    System.out.println("\n[4/5] Enviando Purge 999...");
                    byte[] p999 = buildPurge999(rxTxId);
                    writeFramed(out, p999);

                    // PASO 5: Recibir ACK del Purge
                    System.out.println("\n[5/5] Recibiendo ACK de Purge...");
                    Frame ack = readFramed(in);
                    checkAck("Purge 999", ack);

                    File savedFile = new File(p.filePath);
                    System.out.println("\n==============================================");
                    System.out.println("RECEPCION COMPLETADA EXITOSAMENTE");
                    System.out.println("Archivo guardado: " + savedFile.getAbsolutePath());
                    System.out.println("Tamano          : " + savedFile.length() + " bytes");
                    System.out.println("Bloques recibidos: " + blocksReceived);
                    if (seq > currentSeq) {
                        System.out.println("Secuencia usada : " + seq + " (inicial: " + currentSeq + ")");
                    }
                    System.out.println("==============================================");
                    
                } finally {
                    try { sock.close(); } catch (Exception ignore) {}
                }
                
            } catch (Exception e) {
                // Error en la conexion o protocolo
                if (seq == currentSeq || seq == 99) {
                    lastError = e.getMessage();
                }
                try { if (sock != null) sock.close(); } catch (Exception ignore) {}
                
                // Si es el ultimo intento o hubo un error critico, no continuar
                if (seq == 99 || e instanceof SocketException) {
                    break;
                }
            }
        }
        
        // Si no se encontro archivo despues de intentar todas las secuencias
        if (!found) {
            System.err.println("\n==============================================");
            System.err.println("ERROR: NO SE ENCONTRO ARCHIVO DISPONIBLE");
            System.err.println("==============================================");
            System.err.println("Transmission ID base: " + txId.substring(0, 12));
            System.err.println("Secuencias buscadas : " + currentSeq + " a 99");
            if (lastError != null) {
                System.err.println("Ultimo error del MIP: " + lastError);
            }
            System.err.println("==============================================");
            throw new FileNotFoundException(
                "No se encontro archivo disponible con Transmission ID " + 
                txId.substring(0, 12) + "XX (secuencias " + currentSeq + "-99). " +
                "Ultimo error: " + (lastError != null ? lastError : "Conexion fallida"));
        }
    }

    /* ========================================================================
     * CONSTRUCTORES DE MENSAJES - Segun especificacion Mastercard
     * ======================================================================== */

    /**
     * Construye Header 004 "To Mastercard" - Pagina 49
     * 
     * Estructura (60 bytes total - extendido del original 36 bytes):
     * - Request Code  : "004" (3 bytes EBCDIC)
     * - Record Type   : "01" (2 bytes EBCDIC)
     * - Transmission ID: 14 bytes EBCDIC (RtttEEEEEJJJSS)
     * - Filler        : 17 bytes (pos 20-36)
     * - Reserved      : 4 bytes (pos 37-40) - Futuro: file size
     * - Reserved      : 4 bytes (pos 41-44) - Futuro: sequence number
     * - Filler        : 16 bytes (pos 45-60)
     * 
     * @param txId14 Transmission ID de 14 caracteres
     * @return Byte array con el header completo
     */
    private static byte[] buildHeader004(String txId14) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(60);
        b.write(ebcdic("004"));              // pos 1-3: Request Code
        b.write(ebcdic("01"));               // pos 4-5: Record Type
        b.write(ebcdic(txId14));             // pos 6-19: Transmission ID (14 chars)
        
        // pos 20-36: Filler (17 bytes)
        for (int i = 0; i < 17; i++) b.write(0x00);
        
        // pos 37-40: Reserved para file size futuro (4 bytes)
        for (int i = 0; i < 4; i++) b.write(0x00);
        
        // pos 41-44: Reserved para sequence number futuro (4 bytes)
        for (int i = 0; i < 4; i++) b.write(0x00);
        
        // pos 45-60: Filler (16 bytes)
        for (int i = 0; i < 16; i++) b.write(0x00);
        
        return b.toByteArray();
    }

    /**
     * Construye Request 101 "From Mastercard" - Pagina 51
     * 
     * Estructura (19 bytes total):
     * - Request Code  : "101" (3 bytes EBCDIC)
     * - Record Type   : "01" (2 bytes EBCDIC)
     * - Transmission ID: 14 bytes EBCDIC (TtttEEEEEJJJSS)
     * 
     * Nota: Se puede enviar un request generico con solo 4 bytes (T+tipo)
     * y rellenando el resto con espacios (0x40) o nulos (0x00), pero aqui
     * usamos el ID completo para mayor especificidad.
     * 
     * @param txId14 Transmission ID de 14 caracteres
     * @return Byte array con el request completo
     */
    private static byte[] buildRequest101(String txId14) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(19);
        b.write(ebcdic("101"));              // pos 1-3: Request Code
        b.write(ebcdic("01"));               // pos 4-5: Record Type
        b.write(ebcdic(txId14));             // pos 6-19: Transmission ID (14 chars)
        return b.toByteArray();
    }

    /**
     * Construye Trailer 998 - Pagina 50
     * 
     * Estructura (11 bytes total):
     * - Request Code  : "998" (3 bytes EBCDIC)
     * - Record Type   : "01" (2 bytes EBCDIC)
     * - Return Code   : "00" (2 bytes EBCDIC) - OK
     * - Number of Blocks: 4 bytes Big-Endian
     * 
     * El count DEBE incluir:
     * - Todos los bloques de datos enviados
     * - El trailer mismo (+ 1)
     * 
     * @param dataBlocks Numero de bloques de datos enviados
     * @return Byte array con el trailer completo
     */
    private static byte[] buildTrailer998(int dataBlocks) throws IOException {
        int count = dataBlocks + 1;  // Incluye el trailer segun especificacion
        ByteArrayOutputStream b = new ByteArrayOutputStream(11);
        b.write(ebcdic("998"));              // pos 1-3: Request Code
        b.write(ebcdic("01"));               // pos 4-5: Record Type
        b.write(ebcdic("00"));               // pos 6-7: Return Code (OK)
        b.write(intTo4BE(count), 0, 4);      // pos 8-11: Count en Big-Endian
        return b.toByteArray();
    }

    /**
     * Construye Purge 999 - Pagina 54
     * 
     * Estructura (21 bytes total):
     * - Request Code  : "999" (3 bytes EBCDIC)
     * - Record Type   : "01" (2 bytes EBCDIC)
     * - Return Code   : "00" (2 bytes EBCDIC)
     * - Transmission ID: 14 bytes EBCDIC
     * 
     * El purge 999 le indica al MIP que puede marcar el archivo como 
     * purgeable (eliminable) despues de una recepcion exitosa.
     * 
     * @param txId14 Transmission ID de 14 caracteres
     * @return Byte array con el purge completo
     */
    private static byte[] buildPurge999(String txId14) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(21);
        b.write(ebcdic("999"));              // pos 1-3: Request Code
        b.write(ebcdic("01"));               // pos 4-5: Record Type
        b.write(ebcdic("00"));               // pos 6-7: Return Code
        b.write(ebcdic(txId14));             // pos 8-21: Transmission ID (14 chars)
        return b.toByteArray();
    }

    /* ========================================================================
     * FRAMING PROTOCOL - 2 bytes longitud + payload
     * ======================================================================== */

    /**
     * Escribe un frame con prefijo de longitud de 2 bytes Big-Endian
     * 
     * Formato del frame:
     * [2 bytes longitud Big-Endian][payload]
     * 
     * @param out Stream de salida
     * @param payload Datos a enviar
     */
    private static void writeFramed(OutputStream out, byte[] payload) throws IOException {
        int len = payload.length;
        out.write((len >>> 8) & 0xFF);  // High byte
        out.write(len & 0xFF);           // Low byte
        out.write(payload);
        out.flush();
    }

    /**
     * Lee un frame con prefijo de longitud de 2 bytes Big-Endian
     * 
     * @param in Stream de entrada
     * @return Frame leido con los datos
     */
    private static Frame readFramed(InputStream in) throws IOException {
        int hi = in.read();
        int lo = in.read();
        if (hi < 0 || lo < 0) {
            throw new EOFException("Conexion cerrada esperando longitud de frame");
        }
        
        int len = ((hi & 0xFF) << 8) | (lo & 0xFF);
        byte[] buf = new byte[len];
        int off = 0;
        
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new EOFException("Conexion cerrada leyendo frame de " + len + " bytes");
            }
            off += n;
        }
        
        return new Frame(buf);
    }

    /* ========================================================================
     * VALIDACION DE ACKNOWLEDGEMENTS
     * ======================================================================== */

    /**
     * Valida respuesta ACK del MIP en formato 998
     * 
     * El MIP responde con mensajes 998 para todos los ACKs:
     * - Header ACK
     * - Trailer ACK
     * - Purge ACK
     * 
     * Formato esperado (paginas 50-51):
     * - Request Code: "998" (pos 1-3)
     * - Record Type : "01" o variante como "6A" (pos 4-5)
     * - Return Code : "00" = OK, otros = error (pos 6-7)
     * 
     * @param stage Nombre de la etapa para mensajes
     * @param f Frame recibido del MIP
     */
    private static void checkAck(String stage, Frame f) {
        String code = f.asEbcdic(0, 3);
        
        // Validar que sea mensaje 998
        if (!"998".equals(code)) {
            System.out.println(stage + " - Respuesta no es 998, raw=" + f.hex());
            // No abortamos porque algunos nodos envian ACKs intermedios
            return;
        }
        
        String type = f.asEbcdic(3, 2);  // "01", "6A", etc.
        String rc   = f.asEbcdic(5, 2);  // "00" = OK
        
        if (!"00".equals(rc)) {
            System.err.println(stage + " ERROR -> 998/" + type + " rc=" + rc);
            System.err.println("Datos raw: " + f.hex());
            throw new RuntimeException(stage + " rechazado por MIP (rc=" + rc + ")");
        } else {
            System.out.println("  " + stage + " OK -> 998/" + type + " rc=00");
        }
    }

    /* ========================================================================
     * UTILIDADES DE CONVERSION
     * ======================================================================== */

    /**
     * Convierte string a bytes EBCDIC
     */
    private static byte[] ebcdic(String s) { 
        return s.getBytes(EBCDIC); 
    }

    /**
     * Convierte un caracter a byte EBCDIC
     */
    private static byte ebcdicByte(char c) { 
        return ("" + c).getBytes(EBCDIC)[0]; 
    }

    /**
     * Convierte entero de 4 bytes a Big-Endian
     */
    private static byte[] intTo4BE(int v) {
        return new byte[] {
            (byte)((v >>> 24) & 0xFF),
            (byte)((v >>> 16) & 0xFF),
            (byte)((v >>> 8)  & 0xFF),
            (byte)(v & 0xFF)
        };
    }

    /**
     * Convierte array de bytes a string hexadecimal para debug
     * 
     * @param data Array de bytes
     * @param offset Posicion inicial
     * @param length Numero de bytes a convertir
     * @return String con representacion hexadecimal
     */
    private static String hexBytes(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length && i < data.length; i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    /**
     * Normaliza Transmission ID a 14 caracteres
     * 
     * Formato segun pagina 32:
     * - RtttEEEEEJJJSS (para envio TO Mastercard)
     * - TtttEEEEEJJJSS (para recepcion FROM Mastercard)
     * 
     * Donde:
     * - R/T: Direction indicator (1 char)
     * - ttt: Transmission Type (3 digitos)
     * - EEEEE: Endpoint Number (5 digitos)
     * - JJJ: Julian Day del ano (001-366)
     * - SS: Sequence Number (01-99)
     * 
     * Si se proporciona formato corto (9 chars), se autocompleta con:
     * - JJJ: Dia juliano actual
     * - SS: "01"
     * 
     * @param raw Transmission ID en formato corto (9) o completo (14)
     * @param expectedPrefix Prefijo esperado 'R' o 'T'
     * @return Transmission ID normalizado a 14 caracteres
     */
    private static String normalizeTransmissionId(String raw, char expectedPrefix) {
        if (raw == null) {
            throw new IllegalArgumentException("--ipmname es requerido");
        }
        
        String s = raw.trim();
        
        // Validar que comience con el prefijo correcto
        if (!s.isEmpty() && s.charAt(0) != expectedPrefix) {
            throw new IllegalArgumentException(
                "Transmission ID debe empezar con '" + expectedPrefix + 
                "' para este modo de operacion");
        }
        
        // Si ya tiene 14 caracteres, retornar tal cual
        if (s.length() == 14) {
            return s;
        }
        
        // Si tiene 9 caracteres (formato corto), autocompletar
        if (s.length() == 9) {
            Calendar cal = Calendar.getInstance();
            int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);
            String jjj = String.format("%03d", dayOfYear);
            String ss  = "01";
            return s + jjj + ss;
        }
        
        // Longitud invalida
        throw new IllegalArgumentException(
            "--ipmname debe tener 9 o 14 caracteres. " +
            "Formato: " + expectedPrefix + "tttEEEEE o " + expectedPrefix + "tttEEEEEJJJSS. " +
            "Ejemplo: " + expectedPrefix + "11902840");
    }

    /* ========================================================================
     * CLASES AUXILIARES
     * ======================================================================== */

    /**
     * Clase Frame - Representa un frame recibido del MIP
     * 
     * Proporciona metodos de utilidad para extraer datos del frame:
     * - asEbcdic: Extrae string EBCDIC
     * - asInt: Extrae entero Big-Endian
     * - hex: Representa el frame en hexadecimal
     */
    private static final class Frame {
        final byte[] data;
        
        Frame(byte[] d) { 
            this.data = d; 
        }

        /**
         * Extrae substring EBCDIC del frame
         * @param start Posicion inicial (0-based)
         * @param len Longitud a extraer
         * @return String decodificado de EBCDIC
         */
        String asEbcdic(int start, int len) {
            if (start + len > data.length) return "";
            try { 
                return new String(data, start, len, EBCDIC); 
            } catch (Exception e) { 
                return ""; 
            }
        }

        /**
         * Extrae entero Big-Endian de 4 bytes del frame
         * @param start Posicion inicial (0-based)
         * @param len Numero de bytes (1-4)
         * @return Valor entero
         */
        int asInt(int start, int len) {
            if (start + len > data.length) return 0;
            int result = 0;
            for (int i = 0; i < len; i++) {
                result = (result << 8) | (data[start + i] & 0xFF);
            }
            return result;
        }

        /**
         * Convierte frame completo a representacion hexadecimal
         * @return String hexadecimal
         */
        String hex() {
            StringBuilder sb = new StringBuilder();
            for (byte b : data) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        }
    }

    /**
     * Clase Params - Parametros de linea de comandos
     * 
     * Parsea y almacena los parametros necesarios para la operacion:
     * - mode: "send" o "receive"
     * - ip: Direccion del MIP
     * - port: Puerto del MIP
     * - filePath: Path del archivo IPM
     * - ipmName: Transmission ID
     */
    private static final class Params {
        String mode;
        String ip;
        int port;
        String filePath;
        String ipmName;

        /**
         * Parsea argumentos de linea de comandos
         * 
         * Formatos soportados:
         * - --param=valor
         * - --param valor
         * 
         * @param a Array de argumentos
         * @return Objeto Params parseado, o null si faltan parametros
         */
        static Params parse(String[] a) {
            Params p = new Params();
            
            for (int i = 0; i < a.length; i++) {
                String s = a[i];
                
                if (s.startsWith("--mode=")) {
                    p.mode = s.substring(7);
                } else if (s.equals("--mode") && i + 1 < a.length) {
                    p.mode = a[++i];
                    
                } else if (s.startsWith("--ip=")) {
                    p.ip = s.substring(5);
                } else if (s.equals("--ip") && i + 1 < a.length) {
                    p.ip = a[++i];
                    
                } else if (s.startsWith("--port=")) {
                    p.port = Integer.parseInt(s.substring(7));
                } else if (s.equals("--port") && i + 1 < a.length) {
                    p.port = Integer.parseInt(a[++i]);
                    
                } else if (s.startsWith("--file=")) {
                    p.filePath = s.substring(7);
                } else if (s.equals("--file") && i + 1 < a.length) {
                    p.filePath = a[++i];
                    
                } else if (s.startsWith("--ipmname=")) {
                    p.ipmName = s.substring(10);
                } else if (s.equals("--ipmname") && i + 1 < a.length) {
                    p.ipmName = a[++i];
                }
            }
            
            // Validar que todos los parametros requeridos esten presentes
            if (p.mode == null || p.ip == null || p.port == 0 || 
                p.filePath == null || p.ipmName == null) {
                return null;
            }
            
            return p;
        }
    }
}
