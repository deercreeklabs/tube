.PHONY: classes
classes:
	java -jar /opt/avro/avro-tools.jar compile -string schema \
	  doc/Capsule.avsc src/java/
	java -jar /opt/avro/avro-tools.jar compile -string schema \
	  doc/LoginReq.avsc src/java/
	java -jar /opt/avro/avro-tools.jar compile -string schema \
	  doc/LoginRsp.avsc src/java/
