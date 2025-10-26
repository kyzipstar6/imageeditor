cd C:\Users\Public\Documents\java

javac --module-path "dataplotter/lib/javafx-sdk-23.0.2/lib" --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing -d imageeditor/bin/ imageeditor/src/main/java/app/ImageEditor.java 
java --module-path dataplotter/lib/javafx-sdk-23.0.2/lib --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing -cp ".;imageeditor/bin" app.ImageEditor