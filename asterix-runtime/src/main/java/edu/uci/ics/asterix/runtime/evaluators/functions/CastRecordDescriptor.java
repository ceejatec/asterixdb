package edu.uci.ics.asterix.runtime.evaluators.functions;

import java.io.DataOutput;

import edu.uci.ics.asterix.common.functions.FunctionConstants;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptor;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptorFactory;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import edu.uci.ics.asterix.runtime.pointables.ARecordPointable;
import edu.uci.ics.asterix.runtime.pointables.base.IVisitablePointable;
import edu.uci.ics.asterix.runtime.pointables.cast.ACastVisitor;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.utils.Triple;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.runtime.base.IEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.IEvaluatorFactory;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IDataOutputProvider;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class CastRecordDescriptor extends AbstractScalarFunctionDynamicDescriptor {

    protected static final FunctionIdentifier FID_CAST = new FunctionIdentifier(FunctionConstants.ASTERIX_NS,
            "cast-record", 1, true);
    public static final IFunctionDescriptorFactory FACTORY = new IFunctionDescriptorFactory() {
        public IFunctionDescriptor createFunctionDescriptor() {
            return new CastRecordDescriptor();
        }
    };

    private static final long serialVersionUID = 1L;
    private ARecordType reqType;
    private ARecordType inputType;

    public void reset(ARecordType reqType, ARecordType inputType) {
        this.reqType = reqType;
        this.inputType = inputType;
    }

    @Override
    public FunctionIdentifier getIdentifier() {
        return FID_CAST;
    }

    @Override
    public IEvaluatorFactory createEvaluatorFactory(final IEvaluatorFactory[] args) {
        final IEvaluatorFactory recordEvalFactory = args[0];

        return new IEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public IEvaluator createEvaluator(final IDataOutputProvider output) throws AlgebricksException {
                final DataOutput out = output.getDataOutput();
                final ArrayBackedValueStorage recordBuffer = new ArrayBackedValueStorage();
                final IEvaluator recEvaluator = recordEvalFactory.createEvaluator(recordBuffer);

                return new IEvaluator() {
                    final ARecordPointable recAccessor = new ARecordPointable(inputType);
                    final ARecordPointable resultAccessor = new ARecordPointable(reqType);
                    final ACastVisitor castVisitor = new ACastVisitor();
                    final Triple<IVisitablePointable, IAType, Boolean> arg = new Triple<IVisitablePointable, IAType, Boolean>(
                            resultAccessor, reqType, Boolean.FALSE);

                    @Override
                    public void evaluate(IFrameTupleReference tuple) throws AlgebricksException {
                        try {
                            recordBuffer.reset();
                            recEvaluator.evaluate(tuple);
                            recAccessor.set(recordBuffer);
                            recAccessor.accept(castVisitor, arg);
                            out.write(resultAccessor.getByteArray(), resultAccessor.getStartOffset(),
                                    resultAccessor.getLength());
                        } catch (Exception ioe) {
                            throw new AlgebricksException(ioe);
                        }
                    }
                };
            }
        };
    }
}
