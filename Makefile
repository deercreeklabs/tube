.PHONY: classes
classes: doc/capsule.avsc
	java -jar /opt/avro/avro-tools.jar compile schema $< src/java/
