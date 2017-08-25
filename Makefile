.PHONY: classes
classes: doc/capsule.avsc
	java -jar /opt/avro/avro-tools.jar compile -string schema $< src/java/
